# SSH ProxyCommand: tunnel ssh over HTTP CONNECT proxy (incy 127.0.0.1:10809)
# Usage from ssh: -o "ProxyCommand=pwsh -NoProfile -File ssh-proxyconnect.ps1 %h %p"
param(
    [Parameter(Mandatory=$true)][string]$TargetHost,
    [Parameter(Mandatory=$true)][string]$TargetPort
)
$proxyHost = "127.0.0.1"
$proxyPort = 10809
$log = Join-Path $env:TEMP "ssh-proxyconnect.log"
try {
    Add-Content $log "=== CONNECT ${TargetHost}:${TargetPort} via ${proxyHost}:${proxyPort} ==="
    $tcp = [Net.Sockets.TcpClient]::new($proxyHost, $proxyPort)
    $tcp.ReceiveTimeout = 15000
    $s = $tcp.GetStream()
    $req = "CONNECT ${TargetHost}:${TargetPort} HTTP/1.1`r`nHost: ${TargetHost}:${TargetPort}`r`n`r`n"
    $raw = [Text.Encoding]::ASCII.GetBytes($req)
    $s.Write($raw, 0, $raw.Length)
    $s.Flush()

    $enc = [Text.UTF8Encoding]::new()
    $data = [System.Collections.Generic.List[byte]]::new()
    $rbuf = [byte[]]::new(8192)
    $tail = $null
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ($null -eq $tail -and [DateTime]::UtcNow -lt $deadline) {
        $n = $s.Read($rbuf, 0, $rbuf.Length)
        if ($n -le 0) { break }
        for ($i = 0; $i -lt $n; $i++) { $data.Add($rbuf[$i]) }
        $text = $enc.GetString($data.ToArray())
        $idx = $text.IndexOf("`r`n`r`n")
        if ($idx -ge 0) {
            $tStart = $idx + 4
            $tLen = $data.Count - $tStart
            if ($tLen -gt 0) { $tail = $data.GetRange($tStart, $tLen).ToArray() } else { $tail = [byte[]]::new(0) }
        }
    }
    Add-Content $log ("proxy resp head: " + (($enc.GetString($data.ToArray()) -split "`r`n")[0]))
    if (($enc.GetString($data.ToArray()) -split "`r`n")[0] -notmatch "^HTTP/1\.[01] 200") {
        Add-Content $log "FAILED: proxy did not return 200"
        $tcp.Close(); exit 1
    }
    Add-Content $log ("CONNECT OK, tail bytes after header: " + $tail.Length)
    $pipeIn = [Console]::OpenStandardInput()
    $pipeOut = [Console]::OpenStandardOutput()
    if ($tail.Length -gt 0) { $pipeOut.Write($tail, 0, $tail.Length); $pipeOut.Flush() }
    $t1 = [Threading.Tasks.Task]::Run([Action]{ try { $s.CopyTo($pipeOut) } catch { Add-Content $log ("t1 err: " + $_.Exception.Message) } })
    $t2 = [Threading.Tasks.Task]::Run([Action]{ try { $pipeIn.CopyTo($s) } catch { Add-Content $log ("t2 err: " + $_.Exception.Message) } })
    while (-not ($t1.IsCompleted -and $t2.IsCompleted)) { Start-Sleep -Milliseconds 100 }
    Add-Content $log "passthrough ended, closing"
    $tcp.Close()
} catch {
    Add-Content $log ("FATAL: " + $_.Exception.ToString())
    exit 1
}