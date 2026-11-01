# Hear No Dub media bridge.
# Started (hidden) by the mod. Every 500 ms it prints one JSON line with:
#   sessions: what Windows' media controls know (title, position snapshot, play state)
#   windows:  the active tab title of each open browser window
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

$browsers = @('firefox', 'chrome', 'msedge', 'brave', 'opera', 'vivaldi')

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

	Write-Output (ConvertTo-Json -Compress -Depth 4 -InputObject @{ sessions = $sessions; windows = $windows })
	[Console]::Out.Flush()
	Start-Sleep -Milliseconds 500
}
