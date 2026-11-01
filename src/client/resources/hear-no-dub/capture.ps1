# Hear No Dub video capture helper.
# Started (hidden) by the mod while "Show video" is on. Grabs the browser window about
# -Fps times a second, finds the video inside the page (the part that keeps changing),
# scales it down and writes it into a shared file that the mod maps into memory.
# It exits on its own when Minecraft (ParentPid) closes or the mod stops it.
param(
	[int]$ParentPid = 0,
	[string]$Path = '',
	[string]$Proc = 'firefox',
	[int]$Fps = 24,
	[int]$MaxHeight = 480
)

$ErrorActionPreference = 'Stop'
try {
	Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

public static class HndVideo {
	// ------------------------------------------------------------ shared file layout (little-endian int32)
	// 0 magic 'HNDV' | 4 seq (odd while writing) | 8 width | 12 height | 16 status
	// 20 window w | 24 window h | 28 crop x | 32 crop y | 36 crop w | 40 crop h
	// 44 capture ms x10 | 48 heartbeat
	// written by the mod: 52 crop mode (0 auto, 1 manual, 2 whole window for the crop screen)
	//                     56 manual x | 60 manual y | 64 manual w | 68 manual h (window pixels)
	// 72 auto crop x | 76 y | 80 w | 84 h (what auto-detection found, for the crop screen)
	// 88 average colour of the picture, 0xRRGGBB (for the TV glow)
	// 96.. pixels, RGBA, top row first
	public const int HEADER = 96;
	public const int MAX_W = 2560, MAX_H = 1080;
	public const int ST_OK = 0, ST_NO_WINDOW = 1, ST_MINIMIZED = 2, ST_ERROR = 3;

	[StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
	delegate bool EnumProc(IntPtr h, IntPtr l);

	[DllImport("user32.dll")] static extern bool SetProcessDPIAware();
	[DllImport("user32.dll")] static extern bool GetClientRect(IntPtr h, out RECT r);
	[DllImport("user32.dll")] static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
	[DllImport("user32.dll")] static extern bool IsIconic(IntPtr h);
	[DllImport("user32.dll")] static extern bool IsWindow(IntPtr h);
	[DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
	[DllImport("user32.dll")] static extern int GetWindowTextLength(IntPtr h);
	[DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr h, uint cmd);
	[DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
	[DllImport("user32.dll")] static extern bool EnumWindows(EnumProc cb, IntPtr l);
	[DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr CreateFileMapping(IntPtr file, IntPtr attrs, uint protect, uint maxHi, uint maxLo, string name);
	[DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr MapViewOfFile(IntPtr map, uint access, uint offHi, uint offLo, UIntPtr bytes);

	static IntPtr view;

	static void Put(int offset, int value) { Marshal.WriteInt32(view, offset, value); }

	public static void Run(string path, string proc, int fps, int maxHeight, int parentPid) {
		SetProcessDPIAware();
		FileStream fs = new FileStream(path, FileMode.Open, FileAccess.ReadWrite, FileShare.ReadWrite);
		IntPtr map = CreateFileMapping(fs.SafeFileHandle.DangerousGetHandle(), IntPtr.Zero, 4, 0, 0, null);
		if (map == IntPtr.Zero) throw new Exception("CreateFileMapping failed: " + Marshal.GetLastWin32Error());
		view = MapViewOfFile(map, 2, 0, 0, UIntPtr.Zero);
		if (view == IntPtr.Zero) throw new Exception("MapViewOfFile failed: " + Marshal.GetLastWin32Error());

		fps = Math.Max(1, Math.Min(60, fps));
		maxHeight = Math.Max(144, Math.Min(MAX_H, maxHeight));
		int period = 1000 / fps;
		int seq = 0, heartbeat = 0;
		IntPtr hwnd = IntPtr.Zero;
		Stopwatch findTimer = Stopwatch.StartNew(), parentTimer = Stopwatch.StartNew();
		Bitmap src = null, outBmp = null;
		Graphics srcG = null, outG = null;
		int[] pixels = new int[0];
		Detector detector = new Detector();
		double avgMs = 0;

		while (true) {
			Stopwatch frame = Stopwatch.StartNew();
			Put(48, ++heartbeat);
			if (parentPid > 0 && parentTimer.ElapsedMilliseconds > 1000) {
				parentTimer.Restart();
				try { if (Process.GetProcessById(parentPid).HasExited) return; } catch (ArgumentException) { return; }
			}

			if (hwnd == IntPtr.Zero || !IsWindow(hwnd) || findTimer.ElapsedMilliseconds > 2000) {
				hwnd = FindWindow(proc);
				findTimer.Restart();
			}
			if (hwnd == IntPtr.Zero) { Put(16, ST_NO_WINDOW); Thread.Sleep(500); continue; }
			if (IsIconic(hwnd)) { Put(16, ST_MINIMIZED); Thread.Sleep(500); continue; }

			RECT rc;
			GetClientRect(hwnd, out rc);
			int w = rc.R - rc.L, h = rc.B - rc.T;
			if (w < 32 || h < 32) { Put(16, ST_NO_WINDOW); Thread.Sleep(500); continue; }

			if (src == null || src.Width != w || src.Height != h) {
				if (srcG != null) srcG.Dispose();
				if (src != null) src.Dispose();
				src = new Bitmap(w, h, PixelFormat.Format32bppArgb);
				srcG = Graphics.FromImage(src);
				detector.Reset(w, h);
			}
			IntPtr hdc = srcG.GetHdc();
			PrintWindow(hwnd, hdc, 3); // client area + full content (needed for video drawn by the GPU)
			srcG.ReleaseHdc(hdc);

			detector.Feed(src, fps);
			Rectangle full = new Rectangle(0, 0, w, h);
			Rectangle auto = detector.Trimmed();
			Put(72, auto.X); Put(76, auto.Y); Put(80, auto.Width); Put(84, auto.Height);
			Rectangle crop = auto;
			int mode = Marshal.ReadInt32(view, 52);
			if (mode == 2) {
				crop = full;
			} else if (mode == 1) {
				Rectangle manual = new Rectangle(Marshal.ReadInt32(view, 56), Marshal.ReadInt32(view, 60),
						Marshal.ReadInt32(view, 64), Marshal.ReadInt32(view, 68));
				manual.Intersect(full);
				if (manual.Width >= 32 && manual.Height >= 32) crop = manual;
			}

			int oh = Math.Min(maxHeight, crop.Height);
			int ow = (int)Math.Round(crop.Width * (double)oh / crop.Height);
			if (ow > MAX_W) { ow = MAX_W; oh = (int)Math.Round(crop.Height * (double)ow / crop.Width); }
			ow = Math.Max(2, ow); oh = Math.Max(2, oh);
			if (outBmp == null || outBmp.Width != ow || outBmp.Height != oh) {
				if (outG != null) outG.Dispose();
				if (outBmp != null) outBmp.Dispose();
				outBmp = new Bitmap(ow, oh, PixelFormat.Format32bppArgb);
				outG = Graphics.FromImage(outBmp);
				outG.InterpolationMode = InterpolationMode.Bilinear;
				outG.PixelOffsetMode = PixelOffsetMode.Half;
				outG.CompositingMode = CompositingMode.SourceCopy;
				pixels = new int[ow * oh];
			}
			outG.DrawImage(src, new Rectangle(0, 0, ow, oh), crop, GraphicsUnit.Pixel);

			BitmapData bd = outBmp.LockBits(new Rectangle(0, 0, ow, oh), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
			try {
				for (int y = 0; y < oh; y++) Marshal.Copy(IntPtr.Add(bd.Scan0, y * bd.Stride), pixels, y * ow, ow);
			} finally {
				outBmp.UnlockBits(bd);
			}
			// BGRA in memory -> RGBA (what Minecraft's NativeImage wants), fully opaque
			for (int i = 0; i < pixels.Length; i++) {
				int v = pixels[i];
				pixels[i] = (int)(0xFF000000u | ((uint)v & 0x0000FF00u) | (((uint)v >> 16) & 0xFFu) | (((uint)v & 0xFFu) << 16));
			}

			// Average colour of the picture (for the TV glow), sampled every 7th pixel
			long sr = 0, sg = 0, sb = 0, n = 0;
			for (int i = 0; i < pixels.Length; i += 7) {
				int v = pixels[i];
				sr += v & 255; sg += (v >> 8) & 255; sb += (v >> 16) & 255; n++;
			}
			if (n > 0) Put(88, (int)((sr / n) << 16 | (sg / n) << 8 | (sb / n)));

			Put(4, ++seq);            // odd: frame being written
			Thread.MemoryBarrier();
			Put(8, ow); Put(12, oh); Put(16, ST_OK);
			Put(20, w); Put(24, h);
			Put(28, crop.X); Put(32, crop.Y); Put(36, crop.Width); Put(40, crop.Height);
			Marshal.Copy(pixels, 0, IntPtr.Add(view, HEADER), pixels.Length);
			Thread.MemoryBarrier();
			Put(4, ++seq);            // even: frame complete
			Put(0, 0x56444E48);

			double ms = frame.Elapsed.TotalMilliseconds;
			avgMs = avgMs == 0 ? ms : avgMs * 0.9 + ms * 0.1;
			Put(44, (int)(avgMs * 10));
			int left = period - (int)ms;
			if (left > 0) Thread.Sleep(left);
		}
	}

	/** The biggest visible window of the browser (the one with the video, most of the time). */
	static IntPtr FindWindow(string proc) {
		HashSet<uint> pids = new HashSet<uint>();
		foreach (Process p in Process.GetProcessesByName(proc)) pids.Add((uint)p.Id);
		IntPtr best = IntPtr.Zero, minimized = IntPtr.Zero;
		long bestArea = 0;
		EnumWindows(delegate (IntPtr h, IntPtr l) {
			uint pid;
			GetWindowThreadProcessId(h, out pid);
			if (!pids.Contains(pid) || !IsWindowVisible(h) || GetWindowTextLength(h) == 0) return true;
			if (GetWindow(h, 4) != IntPtr.Zero) return true; // owned popups (menus, tooltips)
			if (IsIconic(h)) { if (minimized == IntPtr.Zero) minimized = h; return true; }
			RECT r;
			GetClientRect(h, out r);
			long area = (long)(r.R - r.L) * (r.B - r.T);
			if (area > bestArea) { bestArea = area; best = h; }
			return true;
		}, IntPtr.Zero);
		return best != IntPtr.Zero ? best : minimized;
	}

	/**
	 * Finds the video inside the page: samples the window every 8 px and, once a second, marks
	 * the spots that changed. Spots stay marked for KEEP seconds, so over a few seconds of
	 * cuts and camera moves the whole picture lights up even though in most anime frames only
	 * a character moves. The biggest video-shaped area is the crop. It grows when a bigger
	 * area shows up, and only moves somewhere else if that holds for MOVE_AFTER seconds.
	 */
	public class Detector {
		const int CELL = 8;
		const int KEEP = 8;
		const int MOVE_AFTER = 3;
		int w, h, gw, gh;
		int[] prev;
		byte[] counts;
		int[] lastActive;
		bool[] bright;
		int frames, second, detectedAt;
		bool detected;
		Rectangle pending = Rectangle.Empty;
		int pendingSeen;
		public Rectangle Crop;

		public void Reset(int width, int height) {
			w = width; h = height;
			gw = Math.Max(1, w / CELL); gh = Math.Max(1, h / CELL);
			prev = null;
			counts = new byte[gw * gh];
			lastActive = new int[gw * gh];
			bright = new bool[gw * gh];
			for (int i = 0; i < lastActive.Length; i++) lastActive[i] = -1000;
			frames = 0;
			second = 0;
			detected = false;
			pending = Rectangle.Empty;
			pendingSeen = 0;
			Crop = new Rectangle(0, 0, w, h);
		}

		public void Feed(Bitmap bmp, int fps) {
			BitmapData bd = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
			bool first = prev == null;
			if (first) prev = new int[gw * gh];
			try {
				for (int gy = 0; gy < gh; gy++) {
					IntPtr row = IntPtr.Add(bd.Scan0, (gy * CELL + CELL / 2) * bd.Stride);
					for (int gx = 0; gx < gw; gx++) {
						int v = Marshal.ReadInt32(row, (gx * CELL + CELL / 2) * 4);
						int i = gy * gw + gx;
						if (!first && Diff(v, prev[i]) > 40 && counts[i] < 255) counts[i]++;
						if (!bright[i] && (((v >> 16) & 255) > 40 || ((v >> 8) & 255) > 40 || (v & 255) > 40)) bright[i] = true;
						prev[i] = v;
					}
				}
			} finally {
				bmp.UnlockBits(bd);
			}
			if (++frames >= fps) {
				second++;
				for (int i = 0; i < counts.Length; i++) if (counts[i] >= 2) lastActive[i] = second;
				Evaluate();
				frames = 0;
				Array.Clear(counts, 0, counts.Length);
			}
		}

		static int Diff(int a, int b) {
			return Math.Abs(((a >> 16) & 255) - ((b >> 16) & 255)) + Math.Abs(((a >> 8) & 255) - ((b >> 8) & 255)) + Math.Abs((a & 255) - (b & 255));
		}

		void Evaluate() {
			// spots that changed in the last KEEP seconds, grown by one cell so small gaps join up
			bool[] mask = new bool[gw * gh];
			for (int y = 0; y < gh; y++)
				for (int x = 0; x < gw; x++) {
					if (second - lastActive[y * gw + x] >= KEEP) continue;
					for (int dy = -1; dy <= 1; dy++)
						for (int dx = -1; dx <= 1; dx++) {
							int nx = x + dx, ny = y + dy;
							if (nx >= 0 && ny >= 0 && nx < gw && ny < gh) mask[ny * gw + nx] = true;
						}
				}
			bool[] seen = new bool[gw * gh];
			int bestCount = 0, bx0 = 0, by0 = 0, bx1 = 0, by1 = 0;
			Queue<int> q = new Queue<int>();
			for (int start = 0; start < mask.Length; start++) {
				if (!mask[start] || seen[start]) continue;
				int count = 0, x0 = gw, y0 = gh, x1 = 0, y1 = 0;
				seen[start] = true;
				q.Enqueue(start);
				while (q.Count > 0) {
					int c = q.Dequeue();
					int cx = c % gw, cy = c / gw;
					count++;
					if (cx < x0) x0 = cx;
					if (cx > x1) x1 = cx;
					if (cy < y0) y0 = cy;
					if (cy > y1) y1 = cy;
					if (cx > 0 && mask[c - 1] && !seen[c - 1]) { seen[c - 1] = true; q.Enqueue(c - 1); }
					if (cx < gw - 1 && mask[c + 1] && !seen[c + 1]) { seen[c + 1] = true; q.Enqueue(c + 1); }
					if (cy > 0 && mask[c - gw] && !seen[c - gw]) { seen[c - gw] = true; q.Enqueue(c - gw); }
					if (cy < gh - 1 && mask[c + gw] && !seen[c + gw]) { seen[c + gw] = true; q.Enqueue(c + gw); }
				}
				if (count > bestCount) { bestCount = count; bx0 = x0; by0 = y0; bx1 = x1; by1 = y1; }
			}
			if (bestCount == 0) return; // nothing moved (paused): keep what we have

			Rectangle full = new Rectangle(0, 0, w, h);
			Rectangle r = new Rectangle(bx0 * CELL, by0 * CELL, (bx1 - bx0 + 1) * CELL, (by1 - by0 + 1) * CELL);
			r.Intersect(full);
			if (Area(r) < Area(full) / 25) return; // too small to be the video (an animated icon, a spinner)
			if (Area(r) > Area(full) * 0.85) r = full; // fullscreen video
			r = SnapToVideoShape(r);
			double aspect = r.Width / (double)r.Height;
			if (r != full && (aspect < 1.3 || aspect > 2.6)) return; // not video-shaped: only part of it moved so far

			if (!detected) { Crop = r; detected = true; detectedAt = second; return; }
			Rectangle both = Rectangle.Intersect(r, Crop);
			double keptInside = both.IsEmpty ? 0 : Area(both) / Area(Crop);
			double overlap = Overlap(r, Crop);

			// Clearly bigger and containing the current crop (more of the picture moved): grow,
			// but only if it's seen twice in a row, so a passing hover effect doesn't count.
			if (keptInside > 0.9 && Area(r) > Area(Crop) * 1.1) {
				if (!pending.IsEmpty && Overlap(r, pending) > 0.85) { Crop = r; detectedAt = second; Clear(); }
				else { pending = r; pendingSeen = 1; }
				return;
			}
			// Roughly the same place, or only part of the picture moving inside it: keep the crop
			// exactly as it is (no jitter, no shrinking to a moving character)
			double newInside = both.IsEmpty ? 0 : Area(both) / Area(r);
			if (overlap > 0.6 || keptInside > 0.8 || newInside > 0.9) { Clear(); return; }
			// Somewhere else entirely (page scrolled, layout changed): only move if it stays there
			if (!pending.IsEmpty && Overlap(r, pending) > 0.85) {
				if (++pendingSeen >= MOVE_AFTER) { Crop = r; detectedAt = second; Clear(); }
			} else {
				pending = r;
				pendingSeen = 1;
			}
		}

		void Clear() { pending = Rectangle.Empty; pendingSeen = 0; }

		/**
		 * The crop without black bars: edge rows/columns where nothing has ever been brighter
		 * than near-black are cut off (the player's black background, letterboxing). Waits a
		 * few seconds after the crop changes so a dark scene isn't mistaken for a bar.
		 */
		public Rectangle Trimmed() {
			Rectangle c = Crop;
			if (!detected || second - detectedAt < 3) return c;
			int cx0 = c.X / CELL, cx1 = Math.Min(gw - 1, (c.Right - 1) / CELL);
			int cy0 = c.Y / CELL, cy1 = Math.Min(gh - 1, (c.Bottom - 1) / CELL);
			while (cy0 < cy1 && !RowBright(cy0, cx0, cx1)) cy0++;
			while (cy1 > cy0 && !RowBright(cy1, cx0, cx1)) cy1--;
			while (cx0 < cx1 && !ColBright(cx0, cy0, cy1)) cx0++;
			while (cx1 > cx0 && !ColBright(cx1, cy0, cy1)) cx1--;
			Rectangle t = new Rectangle(cx0 * CELL, cy0 * CELL, (cx1 - cx0 + 1) * CELL, (cy1 - cy0 + 1) * CELL);
			t.Intersect(c);
			return Area(t) >= Area(c) * 0.5 ? t : c; // trimming half of it away means something's off
		}

		bool RowBright(int y, int x0, int x1) {
			for (int x = x0; x <= x1; x++) if (bright[y * gw + x]) return true;
			return false;
		}

		bool ColBright(int x, int y0, int y1) {
			for (int y = y0; y <= y1; y++) if (bright[y * gw + x]) return true;
			return false;
		}

		static double Area(Rectangle r) { return (double)r.Width * r.Height; }

		/** Close to 16:9: grow the short side to exactly 16:9 (edges of dark scenes don't change). */
		Rectangle SnapToVideoShape(Rectangle r) {
			double aspect = r.Width / (double)r.Height;
			if (aspect < 1.5 || aspect > 2.0) return r;
			int cx = r.X + r.Width / 2, cy = r.Y + r.Height / 2;
			int nw = r.Width, nh = r.Height;
			if (aspect < 16.0 / 9) nw = (int)Math.Round(nh * 16.0 / 9); else nh = (int)Math.Round(nw * 9.0 / 16);
			Rectangle s = new Rectangle(cx - nw / 2, cy - nh / 2, nw, nh);
			s.Intersect(new Rectangle(0, 0, w, h));
			return s.Width >= 16 && s.Height >= 16 ? s : r;
		}

		static double Overlap(Rectangle a, Rectangle b) {
			Rectangle i = Rectangle.Intersect(a, b);
			if (i.IsEmpty) return 0;
			double inter = Area(i);
			double union = Area(a) + Area(b) - inter;
			return union <= 0 ? 0 : inter / union;
		}
	}
}
'@
	[HndVideo]::Run($Path, $Proc, $Fps, $MaxHeight, $ParentPid)
} catch {
	[Console]::Error.WriteLine('capture: ' + $_.Exception.Message)
	exit 1
}
