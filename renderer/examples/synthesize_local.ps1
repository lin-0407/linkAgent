param([Parameter(Mandatory=$true)][string]$WorkRoot)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$fixtureRoot = (Resolve-Path -LiteralPath $WorkRoot).Path
$requests = Get-Content -LiteralPath (Join-Path $fixtureRoot 'narration-requests.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$voice = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $chinese = $voice.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -eq 'zh-CN' } | Select-Object -First 1
    if (-not $chinese) { throw '本机未安装中文离线语音，须提供有来源的 PCM WAV，不能改用付费接口。' }
    $voice.SelectVoice($chinese.VoiceInfo.Name)
    $voice.Rate = 1
    foreach ($request in $requests) {
        $target = [System.IO.Path]::GetFullPath((Join-Path $fixtureRoot $request.path))
        if (-not $target.StartsWith($fixtureRoot + [System.IO.Path]::DirectorySeparatorChar)) { throw '测试音频路径越界' }
        $sourcePath = $target + '.source.json'
        if ((Test-Path -LiteralPath $target) -and (Test-Path -LiteralPath $sourcePath)) {
            $previous = Get-Content -LiteralPath $sourcePath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($previous.text -eq $request.text -and $previous.voice -eq $chinese.VoiceInfo.Name -and $previous.rate -eq $voice.Rate -and $previous.audioSha256 -eq (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash) { continue }
        }
        $voice.SetOutputToWaveFile($target)
        $voice.Speak($request.text)
        $voice.SetOutputToNull()
        @{ text = $request.text; voice = $chinese.VoiceInfo.Name; rate = $voice.Rate; audioSha256 = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash } | ConvertTo-Json | Set-Content -LiteralPath $sourcePath -Encoding UTF8
    }
    $source = @{ source = 'Windows 本机离线 System.Speech'; voice = $chinese.VoiceInfo.Name; rate = $voice.Rate; paidCalls = 0; notice = '仅用于固定工程验收，非产品 TTS 能力；字幕按独立音频实测拼接，语义仍需人工核对' }
    $source | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $fixtureRoot 'audio-source.json') -Encoding UTF8
} finally { $voice.Dispose() }
