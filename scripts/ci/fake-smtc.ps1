# A stand-in music player on Windows, for the build server's Windows job only.
#
# Tells Windows it plays a song (System Media Transport Controls, the list the
# volume flyout shows), as Spotify or a browser playing YouTube Music does, so
# the game's reader (common LocalMedia and its PowerShell helper) can be
# checked on a real Windows: LocalMediaWindowsLiveTest must read this song.
# Writes "ready" to the file given once Windows has the song, then stays up.
#
#   powershell -File fake-smtc.ps1 <ready file>
param([string]$Ready = 'fake-smtc.ready')
$ErrorActionPreference = 'Stop'
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $null = [Windows.Media.Playback.MediaPlayer, Windows.Media.Playback, ContentType = WindowsRuntime]
    $null = [Windows.Media.MediaPlaybackType, Windows.Media, ContentType = WindowsRuntime]
    $null = [Windows.Media.SystemMediaTransportControlsTimelineProperties, Windows.Media, ContentType = WindowsRuntime]
    $player = New-Object Windows.Media.Playback.MediaPlayer
    # The player would drive the controls itself; this script does.
    $player.CommandManager.IsEnabled = $false
    $smtc = $player.SystemMediaTransportControls
    $smtc.IsEnabled = $true
    $smtc.IsPlayEnabled = $true
    $smtc.IsPauseEnabled = $true
    $smtc.IsNextEnabled = $true
    $smtc.IsPreviousEnabled = $true
    $u = $smtc.DisplayUpdater
    $u.Type = [Windows.Media.MediaPlaybackType]::Music
    $u.MusicProperties.Title = 'Windows Test Song'
    $u.MusicProperties.Artist = 'The Runner Testers'
    $u.MusicProperties.AlbumTitle = 'Breeze CI'
    $u.Update()
    $smtc.PlaybackStatus = [Windows.Media.MediaPlaybackStatus]::Playing
    $t = New-Object Windows.Media.SystemMediaTransportControlsTimelineProperties
    $t.StartTime = [TimeSpan]::Zero
    $t.MinSeekTime = [TimeSpan]::Zero
    $t.Position = [TimeSpan]::FromSeconds(42)
    $t.MaxSeekTime = [TimeSpan]::FromSeconds(201)
    $t.EndTime = [TimeSpan]::FromSeconds(201)
    $smtc.UpdateTimelineProperties($t)
    Set-Content -Path $Ready -Value 'ready'
    Write-Output 'stand-in player: ready'
    Start-Sleep -Seconds 600
} catch {
    Set-Content -Path $Ready -Value ('failed: ' + $_.Exception.Message)
    Write-Output ('stand-in player failed: ' + $_.Exception.Message)
    exit 1
}
