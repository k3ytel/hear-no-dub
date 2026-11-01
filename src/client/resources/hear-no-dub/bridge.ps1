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
