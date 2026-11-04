# Hear No Dub media bridge.
# Started (hidden) by the mod. Every 500 ms it prints one JSON line with:
#   sessions: what Windows' media controls know (title, position snapshot, play state)
#   windows:  the active tab title of each open browser window
#   vol:      the result of the last volume change (only after one was asked for)
# It exits on its own when Minecraft (ParentPid) closes.
param([int]$ParentPid = 0)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

try {
	Add-Type -AssemblyName System.Runtime.WindowsRuntime
	$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
		$_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
		$_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
	function Await($op, $type) {
		$t = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
		$t.Wait(2000) | Out-Null
		$t.Result
	}
	$mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
	$propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
	$mgr = Await ($mgrType::RequestAsync()) $mgrType
} catch {
	Write-Output (ConvertTo-Json -Compress -InputObject @{ error = ('init: ' + $_.Exception.Message) })
	[Console]::Out.Flush()
	exit 1
}

# Per-app volume (the same thing as the app's slider in Windows' volume mixer).
# Optional: if this fails to compile, volume commands just report -1.
$volumeReady = $false
try {
	Add-Type -Language CSharp -TypeDefinition @'
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace HearNoDub {
	[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
	internal class MMDeviceEnumerator {}

	[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
	internal interface IMMDeviceEnumerator {
		int NotImpl_EnumAudioEndpoints();
		[PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
	}

	[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
	internal interface IMMDevice {
		[PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
	}

	[Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
	internal interface IAudioSessionManager2 {
		int NotImpl_GetAudioSessionControl();
		int NotImpl_GetSimpleAudioVolume();
		[PreserveSig] int GetSessionEnumerator(out IAudioSessionEnumerator sessions);
	}

	[Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
	internal interface IAudioSessionEnumerator {
		[PreserveSig] int GetCount(out int count);
		[PreserveSig] int GetSession(int index, out IAudioSessionControl2 session);
	}

	[Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
	internal interface IAudioSessionControl2 {
		int NotImpl_GetState();
		int NotImpl_GetDisplayName();
		int NotImpl_SetDisplayName();
		int NotImpl_GetIconPath();
		int NotImpl_SetIconPath();
		int NotImpl_GetGroupingParam();
		int NotImpl_SetGroupingParam();
		int NotImpl_RegisterAudioSessionNotification();
		int NotImpl_UnregisterAudioSessionNotification();
		int NotImpl_GetSessionIdentifier();
		int NotImpl_GetSessionInstanceIdentifier();
		[PreserveSig] int GetProcessId(out uint pid);
	}

	[Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
	internal interface ISimpleAudioVolume {
		[PreserveSig] int SetMasterVolume(float level, ref Guid eventContext);
		[PreserveSig] int GetMasterVolume(out float level);
	}

	[Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
	internal interface IAudioMeterInformation {
		[PreserveSig] int GetPeakValue(out float peak);
	}

	[Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
	internal interface IAudioClient {
		[PreserveSig] int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);
		[PreserveSig] int GetBufferSize(out uint frames);
		[PreserveSig] int GetStreamLatency(out long latency);
		[PreserveSig] int GetCurrentPadding(out uint padding);
		[PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
		[PreserveSig] int GetMixFormat(out IntPtr format);
		[PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
		[PreserveSig] int Start();
		[PreserveSig] int Stop();
		[PreserveSig] int Reset();
		[PreserveSig] int SetEventHandle(IntPtr handle);
		[PreserveSig] int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
	}

	[Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
	internal interface IAudioCaptureClient {
		[PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out long devicePosition, out long qpcPosition);
		[PreserveSig] int ReleaseBuffer(uint frames);
		[PreserveSig] int GetNextPacketSize(out uint frames);
	}

	/// Bass / mids / treble of everything the speakers play (WASAPI loopback), so lyrics can
	/// bounce on the kick and sparkle on the hi-hats. Written as three float32 at offset 20 of
	/// audio.bin, next to the per-app loudness.
	public static class AudioBands {
		static System.Threading.Thread thread;
		static volatile bool wanted;
		static string file;

		public static void Run(bool on, string path) {
			wanted = on;
			file = path;
			if (on && thread == null) {
				thread = new System.Threading.Thread(Loop);
				thread.IsBackground = true;
				thread.SetApartmentState(System.Threading.ApartmentState.MTA);
				thread.Start();
			}
		}

		static void Loop() {
			while (true) {
				try {
					if (!wanted) { System.Threading.Thread.Sleep(300); continue; }
					Capture();
				} catch (Exception) {
					System.Threading.Thread.Sleep(2000);
				}
			}
		}

		static void Capture() {
			IMMDeviceEnumerator devices = (IMMDeviceEnumerator)(new MMDeviceEnumerator());
			IMMDevice speakers;
			if (devices.GetDefaultAudioEndpoint(0, 1, out speakers) != 0 || speakers == null) throw new Exception("no speakers");
			Guid iid = typeof(IAudioClient).GUID;
			object o;
			if (speakers.Activate(ref iid, 23, IntPtr.Zero, out o) != 0 || o == null) throw new Exception("no audio client");
			IAudioClient client = (IAudioClient)o;
			IntPtr fmt;
			if (client.GetMixFormat(out fmt) != 0) throw new Exception("no mix format");
			int tag = Marshal.ReadInt16(fmt, 0) & 0xFFFF;
			int channels = Marshal.ReadInt16(fmt, 2);
			int rate = Marshal.ReadInt32(fmt, 4);
			int bits = Marshal.ReadInt16(fmt, 14);
			bool isFloat = tag == 3 || (tag == 0xFFFE && bits == 32);
			// shared mode, loopback, 1 s buffer
			if (client.Initialize(0, 0x00020000, 10000000, 0, fmt, IntPtr.Zero) != 0) throw new Exception("init failed");
			Guid cid = typeof(IAudioCaptureClient).GUID;
			object co;
			if (client.GetService(ref cid, out co) != 0 || co == null) throw new Exception("no capture client");
			IAudioCaptureClient capture = (IAudioCaptureClient)co;
			client.Start();
			System.IO.FileStream fs = new System.IO.FileStream(file, System.IO.FileMode.OpenOrCreate, System.IO.FileAccess.ReadWrite, System.IO.FileShare.ReadWrite);
			double aLow = 1 - Math.Exp(-2 * Math.PI * 150.0 / rate), aHigh = 1 - Math.Exp(-2 * Math.PI * 4000.0 / rate);
			double low = 0, high = 0;
			double eBass = 0, eMid = 0, eTreble = 0;
			int counted = 0;
			float[] fbuf = new float[0];
			short[] sbuf = new short[0];
			byte[] outb = new byte[12];
			DateTime lastWrite = DateTime.UtcNow;
			try {
				while (wanted) {
					uint packet;
					capture.GetNextPacketSize(out packet);
					while (packet > 0) {
						IntPtr data; uint frames, flags; long dp, qp;
						if (capture.GetBuffer(out data, out frames, out flags, out dp, out qp) != 0) break;
						int n = (int)frames * channels;
						bool silent = (flags & 2) != 0;
						if (!silent && isFloat) {
							if (fbuf.Length < n) fbuf = new float[n];
							Marshal.Copy(data, fbuf, 0, n);
						} else if (!silent && bits == 16) {
							if (sbuf.Length < n) sbuf = new short[n];
							Marshal.Copy(data, sbuf, 0, n);
						}
						for (int f = 0; f < frames; f++) {
							double x = 0;
							if (!silent) {
								for (int c = 0; c < channels; c++) {
									int i = f * channels + c;
									x += isFloat ? fbuf[i] : (bits == 16 ? sbuf[i] / 32768.0 : 0);
								}
								x /= channels;
							}
							low += aLow * (x - low);
							high += aHigh * (x - high);
							double treble = x - high, mid = high - low;
							eBass += low * low;
							eMid += mid * mid;
							eTreble += treble * treble;
							counted++;
						}
						capture.ReleaseBuffer(frames);
						capture.GetNextPacketSize(out packet);
					}
					if ((DateTime.UtcNow - lastWrite).TotalMilliseconds >= 16 && counted > 0) {
						lastWrite = DateTime.UtcNow;
						BitConverter.GetBytes((float)Math.Sqrt(eBass / counted)).CopyTo(outb, 0);
						BitConverter.GetBytes((float)Math.Sqrt(eMid / counted)).CopyTo(outb, 4);
						BitConverter.GetBytes((float)Math.Sqrt(eTreble / counted)).CopyTo(outb, 8);
						fs.Seek(20, System.IO.SeekOrigin.Begin);
						fs.Write(outb, 0, 12);
						fs.Flush();
						eBass = eMid = eTreble = 0;
						counted = 0;
					}
					System.Threading.Thread.Sleep(8);
				}
			} finally {
				client.Stop();
				fs.Dispose();
			}
		}
	}

	/// How loud an app is right now, sampled ~60 times a second on its own thread and
	/// written to a small shared file the mod reads every frame:
	/// int32 magic 'HNDA', int32 seq, float32 peak 0..1, int64 unix ms.
	public static class AudioMeter {
		static volatile string watching = "";
		static System.Threading.Thread thread;
		static string file;

		public static void Watch(string processName, string path) {
			watching = processName == null || processName == "off" ? "" : processName;
			file = path;
			if (thread == null && watching.Length > 0) {
				thread = new System.Threading.Thread(Run);
				thread.IsBackground = true;
				thread.SetApartmentState(System.Threading.ApartmentState.MTA);
				thread.Start();
			}
		}

		static void Run() {
			System.Collections.Generic.List<IAudioMeterInformation> meters = new System.Collections.Generic.List<IAudioMeterInformation>();
			string metersFor = null;
			DateTime refreshed = DateTime.MinValue;
			System.IO.FileStream fs = null;
			byte[] buf = new byte[20];
			int seq = 0;
			while (true) {
				try {
					string proc = watching;
					if (proc.Length == 0) { System.Threading.Thread.Sleep(250); continue; }
					if (fs == null) fs = new System.IO.FileStream(file, System.IO.FileMode.OpenOrCreate, System.IO.FileAccess.ReadWrite, System.IO.FileShare.ReadWrite);
					if (proc != metersFor || (DateTime.UtcNow - refreshed).TotalSeconds > 2) {
						meters = Find(proc);
						metersFor = proc;
						refreshed = DateTime.UtcNow;
					}
					float peak = 0f;
					foreach (IAudioMeterInformation m in meters) {
						float v;
						if (m.GetPeakValue(out v) == 0 && v > peak) peak = v;
					}
					seq++;
					BitConverter.GetBytes(0x41444E48).CopyTo(buf, 0);
					BitConverter.GetBytes(seq).CopyTo(buf, 4);
					BitConverter.GetBytes(peak).CopyTo(buf, 8);
					BitConverter.GetBytes((long)(DateTime.UtcNow - new DateTime(1970, 1, 1)).TotalMilliseconds).CopyTo(buf, 12);
					fs.Seek(0, System.IO.SeekOrigin.Begin);
					fs.Write(buf, 0, buf.Length);
					fs.Flush();
				} catch (Exception) {
					meters = new System.Collections.Generic.List<IAudioMeterInformation>();
					metersFor = null;
					System.Threading.Thread.Sleep(500);
				}
				System.Threading.Thread.Sleep(15);
			}
		}

		static System.Collections.Generic.List<IAudioMeterInformation> Find(string processName) {
			System.Collections.Generic.List<IAudioMeterInformation> list = new System.Collections.Generic.List<IAudioMeterInformation>();
			IMMDeviceEnumerator devices = (IMMDeviceEnumerator)(new MMDeviceEnumerator());
			IMMDevice speakers;
			if (devices.GetDefaultAudioEndpoint(0, 1, out speakers) != 0 || speakers == null) return list;
			Guid iid = typeof(IAudioSessionManager2).GUID;
			object o;
			if (speakers.Activate(ref iid, 23, IntPtr.Zero, out o) != 0 || o == null) return list;
			IAudioSessionEnumerator sessions;
			if (((IAudioSessionManager2)o).GetSessionEnumerator(out sessions) != 0 || sessions == null) return list;
			int count;
			sessions.GetCount(out count);
			for (int i = 0; i < count; i++) {
				IAudioSessionControl2 ctl;
				if (sessions.GetSession(i, out ctl) != 0 || ctl == null) continue;
				uint pid;
				if (ctl.GetProcessId(out pid) != 0) continue;
				string name = "";
				try { name = Process.GetProcessById((int)pid).ProcessName; } catch (Exception) { }
				if (!string.Equals(name, processName, StringComparison.OrdinalIgnoreCase)) continue;
				IAudioMeterInformation meter = ctl as IAudioMeterInformation;
				if (meter != null) list.Add(meter);
			}
			return list;
		}
	}

	public static class AppVolume {
		/// Changes the volume of every audio session of the processes with this name
		/// (browsers play sound from several processes). Returns the new level 0..1, or -1.
		public static float Change(string processName, float delta) {
			return Apply(processName, delta, false);
		}

		/// Sets the volume to an exact level 0..1. Returns the new level, or -1.
		public static float Set(string processName, float level) {
			return Apply(processName, level, true);
		}

		static float Apply(string processName, float value, bool absolute) {
			float result = -1f;
			float target = -1f;
			IMMDeviceEnumerator devices = (IMMDeviceEnumerator)(new MMDeviceEnumerator());
			IMMDevice speakers;
			if (devices.GetDefaultAudioEndpoint(0, 1, out speakers) != 0 || speakers == null) return -1f;
			Guid iid = typeof(IAudioSessionManager2).GUID;
			object o;
			if (speakers.Activate(ref iid, 23, IntPtr.Zero, out o) != 0 || o == null) return -1f;
			IAudioSessionEnumerator sessions;
			if (((IAudioSessionManager2)o).GetSessionEnumerator(out sessions) != 0 || sessions == null) return -1f;
			int count;
			sessions.GetCount(out count);
			for (int i = 0; i < count; i++) {
				IAudioSessionControl2 ctl;
				if (sessions.GetSession(i, out ctl) != 0 || ctl == null) continue;
				uint pid;
				if (ctl.GetProcessId(out pid) != 0) continue;
				string name = "";
				try { name = Process.GetProcessById((int)pid).ProcessName; } catch (Exception) { }
				if (!string.Equals(name, processName, StringComparison.OrdinalIgnoreCase)) continue;
				ISimpleAudioVolume vol = ctl as ISimpleAudioVolume;
				if (vol == null) continue;
				if (target < 0f) {
					float current;
					if (vol.GetMasterVolume(out current) != 0) continue;
					target = absolute ? value : (float)(Math.Round((current + value) * 100.0) / 100.0);
					if (target < 0f) target = 0f;
					if (target > 1f) target = 1f;
				}
				Guid ctx = Guid.Empty;
				if (vol.SetMasterVolume(target, ref ctx) == 0) result = target;
			}
			return result;
		}
	}
}
'@
	$volumeReady = $true
} catch {
	[Console]::Error.WriteLine('volume control unavailable: ' + $_.Exception.Message)
}

$browsers = @('firefox', 'chrome', 'msedge', 'brave', 'opera', 'vivaldi')
$cmdDir = Join-Path $PSScriptRoot 'cmd'
$script:vol = $null
$script:volSeq = 0

# Commands from the mod: files "<id>.cmd" containing one of
#   toggle|pause|play <app id>
#   seek <position ms> <app id>
#   vol <delta 0..1, may be negative> <process name>   (0 = just report the current level)
#   volset <level 0..1> <process name>
#   meter <process name|off>   (writes the app's loudness to audio.bin ~60 times a second)
function Invoke-Commands {
	if (-not (Test-Path $cmdDir)) { return }
	foreach ($f in (Get-ChildItem -Path $cmdDir -Filter '*.cmd' -ErrorAction SilentlyContinue | Sort-Object Name)) {
		try {
			$line = (Get-Content -LiteralPath $f.FullName -Raw).Trim()
			Remove-Item -LiteralPath $f.FullName -Force
			$parts = $line -split ' ', 3
			if ($parts.Count -lt 2) { continue }

			if ($parts[0] -eq 'volset') {
				# Volume fade with distance: set an exact level, no report (it happens many times a second)
				if ($volumeReady -and $parts.Count -ge 3) {
					try {
						$level = [float]::Parse($parts[1], [System.Globalization.CultureInfo]::InvariantCulture)
						[HearNoDub.AppVolume]::Set($parts[2], $level) | Out-Null
					} catch {}
				}
				continue
			}
			if ($parts[0] -eq 'meter') {
				# How loud an app is, for lyrics that move with the music: meter <process name|off>
				if ($volumeReady) {
					try { [HearNoDub.AudioMeter]::Watch($parts[1], (Join-Path $PSScriptRoot 'audio.bin')) } catch {}
					try { [HearNoDub.AudioBands]::Run(($parts[1] -ne 'off'), (Join-Path $PSScriptRoot 'audio.bin')) } catch {}
				}
				continue
			}
			if ($parts[0] -eq 'vol') {
				if ($parts.Count -lt 3) { continue }
				$level = -1
				if ($volumeReady) {
					try {
						$delta = [float]::Parse($parts[1], [System.Globalization.CultureInfo]::InvariantCulture)
						$level = [HearNoDub.AppVolume]::Change($parts[2], $delta)
					} catch { $level = -1 }
				}
				$script:volSeq++
				$script:vol = @{ proc = $parts[2]; level = [double]$level; seq = $script:volSeq }
				# Report right away, so the in-game popup doesn't wait for the next snapshot
				Write-Output (ConvertTo-Json -Compress -Depth 3 -InputObject @{ vol = $script:vol })
				[Console]::Out.Flush()
				continue
			}

			$app = if ($parts[0] -eq 'seek') { if ($parts.Count -ge 3) { $parts[2] } else { '' } } else { ($line -split ' ', 2)[1] }
			foreach ($s in $mgr.GetSessions()) {
				if ($s.SourceAppUserModelId -ne $app) { continue }
				$op = $null
				switch ($parts[0]) {
					'toggle' { $op = $s.TryTogglePlayPauseAsync() }
					'pause'  { $op = $s.TryPauseAsync() }
					'play'   { $op = $s.TryPlayAsync() }
					'seek'   { $op = $s.TryChangePlaybackPositionAsync([long]$parts[1] * 10000) }
				}
				if ($op) { Await $op ([bool]) | Out-Null }
			}
		} catch {}
	}
}

while ($true) {
	if ($ParentPid -gt 0 -and -not (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue)) { exit 0 }

	$sessions = @()
	try {
		foreach ($s in $mgr.GetSessions()) {
			try {
				$p = Await ($s.TryGetMediaPropertiesAsync()) $propsType
				$tl = $s.GetTimelineProperties()
				$sessions += @{
					app     = [string]$s.SourceAppUserModelId
					title   = [string]$p.Title
					artist  = [string]$p.Artist
					album   = [string]$p.AlbumTitle
					pos     = [long]$tl.Position.TotalMilliseconds
					end     = [long]$tl.EndTime.TotalMilliseconds
					updated = [long]$tl.LastUpdatedTime.ToUnixTimeMilliseconds()
					status  = [string]$s.GetPlaybackInfo().PlaybackStatus
				}
			} catch {}
		}
	} catch {}

	$windows = @()
	try {
		foreach ($pr in (Get-Process -Name $browsers -ErrorAction SilentlyContinue)) {
			if ($pr.MainWindowTitle) { $windows += @{ proc = $pr.ProcessName; title = $pr.MainWindowTitle } }
		}
	} catch {}

	$out = @{ sessions = $sessions; windows = $windows }
	if ($script:vol) { $out.vol = $script:vol }
	Write-Output (ConvertTo-Json -Compress -Depth 4 -InputObject $out)
	[Console]::Out.Flush()
	for ($k = 0; $k -lt 5; $k++) {
		Invoke-Commands
		Start-Sleep -Milliseconds 100
	}
}
