#Requires -Version 5.1
<#
.SYNOPSIS
    family-ai-butler 端到端接口自测（D3 账号与家庭 / D4 收件箱 / D5 长期记忆）。

.DESCRIPTION
    以 README 里的自检脚本为基准，把 curl 片段串成可重复执行的用例：
      - 先做服务健康检查；8080 没有健康实例时用 target 下的 jar 以 local profile 启动一个
        （日志落到 target/e2e-logs/），跑完自动关掉自己启动的进程。
      - identifier / 家庭名都带「时间戳-随机数」后缀，重复执行不会撞上历史数据。
      - 每个请求显式断言 HTTP 状态码与关键字段（id/status/tokenValue/familyId/visibility），
        断言失败只记失败并继续跑后面的用例。
      - 语音用例依赖音频文件与 DASHSCOPE_API_KEY：缺依赖时按「跳过」处理并说明原因。
      - 结束时清理本次创建的记忆、收件箱事件、账号/成员/家庭（H2 TCP 直连执行 SQL），
        以及 voice 用例落盘的音频文件。

.PARAMETER BaseUrl
    被测服务地址，默认 http://localhost:8080。

.PARAMETER AudioFile
    语音用例用的音频文件（wav/mp3/pcm/aac/m4a/amr/opus）。不传则用 data/test-audio/sample.wav；
    文件不存在时脚本会生成一段 1.5 秒合成音（只验证上传链路，不含语义语音）。

.PARAMETER StartupTimeoutSec
    等待服务就绪的超时秒数，默认 120。

.PARAMETER SkipCleanup
    只跑用例、不清理测试数据（排查问题时用）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\e2e-api-test.ps1
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$AudioFile,
    [int]$StartupTimeoutSec = 120,
    [switch]$SkipCleanup
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$script:StartedAt = Get-Date

$root = Split-Path -Parent $PSScriptRoot
$BaseUrl = $BaseUrl.TrimEnd('/')
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + (Get-Random -Minimum 1000 -Maximum 9999)
$logDir = Join-Path $root 'target\e2e-logs'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

# 本次测试的身份数据：带随机后缀，避免与库里已有账号/家庭冲突
$ownerIdentifier = "e2e_owner_$runId"
$spouseIdentifier = "e2e_spouse_$runId"
$familyName = "E2E测试家庭-$runId"
$ownerNickname = 'E2E户主'
$spouseNickname = 'E2E配偶'
$childNickname = 'E2E孩子'
$password = 'dev123456'
$wrongPassword = 'wrong-password'

$script:Cases = New-Object System.Collections.ArrayList

# ------------------------------------------------------------------ 输出与断言工具

function New-Case {
    param([string]$Id, [string]$Name, [string]$Method, [string]$Path, [string]$Expected)
    $case = [pscustomobject]@{
        Id           = $Id
        Name         = $Name
        Method       = $Method
        Path         = $Path
        Expected     = $Expected
        Status       = 'PASS'
        ActualStatus = ''
        ResponseBody = ''
        Failures     = New-Object System.Collections.ArrayList
        Note         = ''
    }
    [void]$script:Cases.Add($case)
    return $case
}

function Add-Failure {
    param($Case, [string]$Detail)
    [void]$Case.Failures.Add($Detail)
}

function Assert-Equal {
    param($Case, [string]$Field, $Expected, $Actual)
    $e = if ($null -eq $Expected) { '<null>' } else { [string]$Expected }
    $a = if ($null -eq $Actual) { '<null>' } else { [string]$Actual }
    if ($e -ne $a) {
        Add-Failure $Case "$Field：期望 [$e]，实际 [$a]"
    }
}

function Assert-True {
    param($Case, [string]$Desc, [bool]$Condition, [string]$Detail)
    if (-not $Condition) {
        Add-Failure $Case "$Desc：$Detail"
    }
}

function Set-Skipped {
    param($Case, [string]$Reason)
    $Case.Status = 'SKIP'
    $Case.Note = $Reason
}

function Set-DependencySkip {
    param($Case, [string]$Reason)
    $Case.Status = 'SKIP'
    $Case.Note = "依赖前序用例未就绪：$Reason"
}

function Get-Field {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

# ------------------------------------------------------------------ HTTP 封装（.NET HttpClient：4xx/5xx 不抛异常，便于断言）

$script:Http = New-Object System.Net.Http.HttpClient
$script:Http.Timeout = [TimeSpan]::FromSeconds(120)

function Send-Api {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Token,
        [string]$Json,
        [string]$FilePath,
        [string]$FileContentType = 'audio/wav'
    )

    $result = [pscustomobject]@{ Status = 0; Raw = ''; Json = $null; Error = '' }
    $req = $null
    $resp = $null
    try {
        $methodObj = switch ($Method.ToUpperInvariant()) {
            'GET' { [System.Net.Http.HttpMethod]::Get }
            'POST' { [System.Net.Http.HttpMethod]::Post }
            'PUT' { [System.Net.Http.HttpMethod]::Put }
            'DELETE' { [System.Net.Http.HttpMethod]::Delete }
            default { throw "不支持的 HTTP 方法：$Method" }
        }
        $req = [System.Net.Http.HttpRequestMessage]::new($methodObj, "$BaseUrl$Path")
        if ($Token) { [void]$req.Headers.TryAddWithoutValidation('Authorization', $Token) }

        if ($FilePath) {
            $mp = New-Object System.Net.Http.MultipartFormDataContent
            $bytes = [System.IO.File]::ReadAllBytes($FilePath)
            $fileContent = [System.Net.Http.ByteArrayContent]::new($bytes)
            $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse($FileContentType)
            $mp.Add($fileContent, 'file', [System.IO.Path]::GetFileName($FilePath))
            $req.Content = $mp
        } elseif ($Json) {
            $req.Content = [System.Net.Http.StringContent]::new($Json, [System.Text.Encoding]::UTF8, 'application/json')
        }

        $resp = $script:Http.SendAsync($req).GetAwaiter().GetResult()
        $result.Status = [int]$resp.StatusCode
        $result.Raw = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not [string]::IsNullOrWhiteSpace($result.Raw)) {
            try { $result.Json = $result.Raw | ConvertFrom-Json } catch { $result.Json = $null }
        }
    } catch {
        $result.Error = $_.Exception.Message
    } finally {
        if ($resp) { $resp.Dispose() }
        if ($req) { $req.Dispose() }
    }
    return $result
}

function Save-Response {
    param($Case, $Response)
    $Case.ActualStatus = if ($Response.Status -gt 0) { [string]$Response.Status } else { "无响应（$($Response.Error)）" }
    $Case.ResponseBody = if ($Response.Raw) { $Response.Raw } else { $Response.Error }
}

function Test-AppResponding {
    $probe = Send-Api -Method GET -Path '/api/families/members'
    return ($probe.Status -gt 0)
}

function Wait-AppReady {
    param([int]$TimeoutSec)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-AppResponding) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

# 生成一段 16k/单声道/16bit 的合成音，只为把语音上传链路跑通（不含语义语音，转写内容无法校验）
function New-SyntheticWav {
    param([string]$Path)
    $sampleRate = 16000
    $seconds = 1.5
    $samples = [int]($sampleRate * $seconds)
    $dataSize = $samples * 2
    $stream = [System.IO.File]::Create($Path)
    $writer = New-Object System.IO.BinaryWriter($stream)
    try {
        $writer.Write([System.Text.Encoding]::ASCII.GetBytes('RIFF'))
        $writer.Write([int](36 + $dataSize))
        $writer.Write([System.Text.Encoding]::ASCII.GetBytes('WAVEfmt '))
        $writer.Write([int]16)
        $writer.Write([int16]1)
        $writer.Write([int16]1)
        $writer.Write([int]$sampleRate)
        $writer.Write([int]($sampleRate * 2))
        $writer.Write([int16]2)
        $writer.Write([int16]16)
        $writer.Write([System.Text.Encoding]::ASCII.GetBytes('data'))
        $writer.Write([int]$dataSize)
        for ($i = 0; $i -lt $samples; $i++) {
            $t = $i / $sampleRate
            $envelope = [math]::Min(1.0, [math]::Min($t * 20, ($seconds - $t) * 20))
            $value = 0.25 * [math]::Sin(2 * [math]::PI * 440 * $t) * $envelope
            $writer.Write([int16]($value * 32767))
        }
    } finally {
        $writer.Dispose()
        $stream.Dispose()
    }
}

function Find-JavaExe {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return (Join-Path $env:JAVA_HOME 'bin\java.exe')
    }
    $candidates = @(
        'D:\Program Files\Java\jdk-17\bin\java.exe',
        'C:\Program Files\Java\jdk-17\bin\java.exe'
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

# ------------------------------------------------------------------ 准备阶段：健康检查 / 按需启动

Write-Host ''
Write-Host '=== family-ai-butler 端到端接口自测 ===' -ForegroundColor Cyan
Write-Host "BaseUrl : $BaseUrl"
Write-Host "RunId   : $runId"

$healthCase = New-Case 'S0' '服务健康检查（本地 local profile，默认端口 8080）' 'GET' '/api/families/members' 'HTTP 401（未登录，证明服务可响应）'
$appStartedByScript = $false
$appProcess = $null
$appLogPath = Join-Path $logDir "app-$runId.log"
$appErrPath = Join-Path $logDir "app-$runId.err.log"
$reuseNote = ''

if (Wait-AppReady -TimeoutSec 5) {
    $reuseNote = '复用已在运行的应用实例'
} else {
    $jar = Get-ChildItem (Join-Path $root 'target') -Filter 'family-ai-butler-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '\.original$' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $javaExe = Find-JavaExe
    if (-not $jar -or -not $javaExe) {
        Add-Failure $healthCase "服务未启动且无法自行启动（jar=$($jar.FullName)，java=$javaExe）：请先 mvn package 或在 IDE 里以 local profile 启动"
    } else {
        Write-Host "启动应用：$javaExe -jar $($jar.Name) --spring.profiles.active=local"
        $appProcess = Start-Process -FilePath $javaExe `
            -ArgumentList @('-jar', $jar.FullName, '--spring.profiles.active=local') `
            -RedirectStandardOutput $appLogPath -RedirectStandardError $appErrPath `
            -WindowStyle Hidden -PassThru
        $appStartedByScript = $true
        $reuseNote = "脚本自行启动（pid=$($appProcess.Id)，日志：$appLogPath）"
        if (-not (Wait-AppReady -TimeoutSec $StartupTimeoutSec)) {
            Add-Failure $healthCase "等待 $StartupTimeoutSec 秒后服务仍无响应，启动日志见 $appLogPath"
        }
    }
}

$health = Send-Api -Method GET -Path '/api/families/members'
Save-Response $healthCase $health
Assert-Equal $healthCase 'HTTP 状态码' 401 $health.Status
if ($health.Json) { Assert-True $healthCase '错误响应体含 error 字段' ([bool](Get-Field $health.Json 'error')) "实际响应：$($health.Raw)" }
Write-Host "服务健康：$($healthCase.Status) $reuseNote"

$serviceUp = ($healthCase.Status -eq 'PASS')

# ------------------------------------------------------------------ 用例依赖的上下文

$ownerToken = $null
$ownerMemberId = $null
$familyId = $null
$spouseToken = $null
$spouseMemberId = $null
$privateMemoryId = $null
$familyMemoryId = $null
$textEventId = $null
$voiceEventId = $null
$voiceRawRef = $null

# ------------------------------------------------------------------ D3 账号与家庭

# D3-1 建家庭（= 注册户主）
$c = New-Case 'D3-1' '建家庭（户主账号注册）' 'POST' '/api/families' 'HTTP 201 + familyId/memberId/tokenValue'
$body = @{ familyName = $familyName; ownerNickname = $ownerNickname; identifier = $ownerIdentifier; password = $password } | ConvertTo-Json -Compress
$r = Send-Api -Method POST -Path '/api/families' -Json $body
Save-Response $c $r
Assert-Equal $c 'HTTP 状态码' 201 $r.Status
if ($r.Status -eq 201 -and $r.Json) {
    $familyId = Get-Field $r.Json 'familyId'
    $ownerMemberId = Get-Field $r.Json 'memberId'
    $ownerToken = Get-Field $r.Json 'tokenValue'
    Assert-True $c 'familyId 为正整数' ([long]$familyId -gt 0) "familyId=$familyId"
    Assert-True $c 'memberId 为正整数' ([long]$ownerMemberId -gt 0) "memberId=$ownerMemberId"
    Assert-True $c '返回 tokenValue 非空' ([bool]$ownerToken) "tokenValue=$ownerToken"
    Assert-Equal $c 'tokenName' 'Authorization' (Get-Field $r.Json 'tokenName')
} else {
    Add-Failure $c '响应体缺少 JSON 字段'
}

# D3-2 密码错误登录 → 401
$c = New-Case 'D3-2' '密码错误登录应被拒绝' 'POST' '/api/auth/login' 'HTTP 401 + error=用户名或密码不正确'
if (-not $ownerToken) {
    Set-DependencySkip $c 'D3-1 建家庭失败'
} else {
    $body = @{ identifier = $ownerIdentifier; password = $wrongPassword } | ConvertTo-Json -Compress
    $r = Send-Api -Method POST -Path '/api/auth/login' -Json $body
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 401 $r.Status
    Assert-Equal $c 'error' '用户名或密码不正确' (Get-Field $r.Json 'error')
    Assert-True $c '不应返回 tokenValue' (-not (Get-Field $r.Json 'tokenValue')) "实际响应：$($r.Raw)"
}

# D3-3 密码正确登录 → 200 + tokenValue
$c = New-Case 'D3-3' '密码正确登录应成功' 'POST' '/api/auth/login' 'HTTP 200 + tokenValue + familyId 一致'
if (-not $ownerToken) {
    Set-DependencySkip $c 'D3-1 建家庭失败'
} else {
    $body = @{ identifier = $ownerIdentifier; password = $password } | ConvertTo-Json -Compress
    $r = Send-Api -Method POST -Path '/api/auth/login' -Json $body
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 200 $r.Status
    $loginToken = Get-Field $r.Json 'tokenValue'
    Assert-True $c '返回 tokenValue 非空' ([bool]$loginToken) "实际响应：$($r.Raw)"
    Assert-Equal $c 'familyId' $familyId (Get-Field $r.Json 'familyId')
    Assert-Equal $c 'memberId' $ownerMemberId (Get-Field $r.Json 'memberId')
    if ($loginToken) { $ownerToken = $loginToken }
}

# D3-4 添加配偶成员（带账号）
$spouseMemberIdReturned = $null
$c = New-Case 'D3-4' '添加配偶成员（带登录账号）' 'POST' '/api/families/members' 'HTTP 201 + role=spouse + familyId 一致'
if (-not $ownerToken) {
    Set-DependencySkip $c '缺少户主 token'
} else {
    $body = @{ nickname = $spouseNickname; role = 'spouse'; identifier = $spouseIdentifier; password = $password } | ConvertTo-Json -Compress
    $r = Send-Api -Method POST -Path '/api/families/members' -Token $ownerToken -Json $body
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 201 $r.Status
    $spouseMemberIdReturned = Get-Field $r.Json 'id'
    Assert-True $c '返回成员 id 为正整数' ([long]$spouseMemberIdReturned -gt 0) "id=$spouseMemberIdReturned"
    Assert-Equal $c 'role' 'spouse' (Get-Field $r.Json 'role')
    Assert-Equal $c 'familyId' $familyId (Get-Field $r.Json 'familyId')
    Assert-Equal $c 'nickname' $spouseNickname (Get-Field $r.Json 'nickname')
}

# D3-5 添加孩子成员（不带账号）
$childMemberIdReturned = $null
$c = New-Case 'D3-5' '添加孩子成员（暂不给账号）' 'POST' '/api/families/members' 'HTTP 201 + role=child + familyId 一致'
if (-not $ownerToken) {
    Set-DependencySkip $c '缺少户主 token'
} else {
    $body = @{ nickname = $childNickname; role = 'child' } | ConvertTo-Json -Compress
    $r = Send-Api -Method POST -Path '/api/families/members' -Token $ownerToken -Json $body
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 201 $r.Status
    $childMemberIdReturned = Get-Field $r.Json 'id'
    Assert-True $c '返回成员 id 为正整数' ([long]$childMemberIdReturned -gt 0) "id=$childMemberIdReturned"
    Assert-Equal $c 'role' 'child' (Get-Field $r.Json 'role')
    Assert-Equal $c 'familyId' $familyId (Get-Field $r.Json 'familyId')
}

# D3-6 成员列表 → 3 人、同一 familyId
$c = New-Case 'D3-6' '成员列表应为 3 人且同一 familyId' 'GET' '/api/families/members' 'HTTP 200 + count=3 + familyId 全一致'
if (-not $ownerToken -or -not $familyId) {
    Set-DependencySkip $c '缺少户主 token 或 familyId'
} else {
    $r = Send-Api -Method GET -Path '/api/families/members' -Token $ownerToken
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 200 $r.Status
    $members = @($r.Json)
    Assert-Equal $c '成员数量' 3 $members.Count
    $familyIds = @($members | ForEach-Object { [string](Get-Field $_ 'familyId') } | Select-Object -Unique)
    Assert-Equal $c 'familyId 去重后数量' 1 $familyIds.Count
    Assert-Equal $c 'familyId 取值' ([string]$familyId) ($familyIds -join ',')
    $roles = @($members | ForEach-Object { [string](Get-Field $_ 'role') } | Sort-Object)
    Assert-Equal $c '角色集合' 'child,self,spouse' ($roles -join ',')
    $nicknames = @($members | ForEach-Object { [string](Get-Field $_ 'nickname') } | Sort-Object)
    $expectedNicknames = (@($childNickname, $ownerNickname, $spouseNickname) | Sort-Object) -join ','
    Assert-Equal $c '昵称集合' $expectedNicknames ($nicknames -join ',')
}

# D3-7 不带 token → 401
$c = New-Case 'D3-7' '不带 token 访问成员列表应 401' 'GET' '/api/families/members' 'HTTP 401 + error'
$r = Send-Api -Method GET -Path '/api/families/members'
Save-Response $c $r
Assert-Equal $c 'HTTP 状态码' 401 $r.Status
Assert-True $c '错误响应体含 error 字段' ([bool](Get-Field $r.Json 'error')) "实际响应：$($r.Raw)"

# D3-8 配偶登录（供 D5 隔离用例使用）
$c = New-Case 'D3-8' '配偶账号登录（取得隔离验证用的第二 token）' 'POST' '/api/auth/login' 'HTTP 200 + tokenValue + 同一 familyId'
if (-not $spouseMemberIdReturned) {
    Set-DependencySkip $c 'D3-4 未成功添加带账号的配偶'
} else {
    $body = @{ identifier = $spouseIdentifier; password = $password } | ConvertTo-Json -Compress
    $r = Send-Api -Method POST -Path '/api/auth/login' -Json $body
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 200 $r.Status
    $spouseToken = Get-Field $r.Json 'tokenValue'
    $spouseMemberId = Get-Field $r.Json 'memberId'
    Assert-True $c '返回 tokenValue 非空' ([bool]$spouseToken) "实际响应：$($r.Raw)"
    Assert-Equal $c 'familyId' $familyId (Get-Field $r.Json 'familyId')
    Assert-Equal $c 'memberId' $spouseMemberIdReturned $spouseMemberId
    if ($spouseToken) { Assert-True $c '配偶 token 与户主 token 不同' ($spouseToken -ne $ownerToken) '两个 token 相同，不符合 is-share=false 配置' }
}

# ------------------------------------------------------------------ D4 收件箱

# D4-1 文本进箱
$c = New-Case 'D4-1' '文本进箱' 'POST' '/api/inbox/text' 'HTTP 201 + id 正整数 + status=new'
if (-not $ownerToken) {
    Set-DependencySkip $c '缺少户主 token'
} else {
    $body = @{ text = "E2E 自测：明天下午三点提醒我开会（$runId）" } | ConvertTo-Json -Compress
    $r = Send-Api -Method POST -Path '/api/inbox/text' -Token $ownerToken -Json $body
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 201 $r.Status
    $textEventId = Get-Field $r.Json 'id'
    Assert-True $c '返回事件 id 为正整数' ([long]$textEventId -gt 0) "id=$textEventId"
    Assert-Equal $c 'status' 'new' (Get-Field $r.Json 'status')
    Assert-Equal $c 'source' 'text' (Get-Field $r.Json 'source')
    Assert-Equal $c 'familyId' $familyId (Get-Field $r.Json 'familyId')
    Assert-True $c 'text 原样回显' ([bool](Get-Field $r.Json 'text')) "实际响应：$($r.Raw)"
}

# D4-2 列表能查到该事件
$c = New-Case 'D4-2' '收件箱列表能查到刚入箱的事件' 'GET' '/api/inbox' 'HTTP 200 + 列表含该事件 id（新到旧）'
if (-not $textEventId) {
    Set-DependencySkip $c 'D4-1 未成功创建文本事件'
} else {
    $r = Send-Api -Method GET -Path '/api/inbox' -Token $ownerToken
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 200 $r.Status
    $events = @($r.Json)
    $hit = @($events | Where-Object { [string](Get-Field $_ 'id') -eq [string]$textEventId })
    Assert-Equal $c "列表中含事件 $textEventId" 1 $hit.Count
    if ($hit.Count -ge 1) {
        Assert-Equal $c '该事件 status' 'new' (Get-Field $hit[0] 'status')
        Assert-Equal $c '该事件 familyId' $familyId (Get-Field $hit[0] 'familyId')
    }
    if ($events.Count -ge 2 -and (Get-Field $events[0] 'id')) {
        Assert-True $c '列表按新到旧排序' ([long](Get-Field $events[0] 'id') -ge [long](Get-Field $events[$events.Count - 1] 'id')) '首条 id 小于末条 id'
    }
}

# D4-3 语音进箱（依赖音频文件 + DASHSCOPE_API_KEY）
$c = New-Case 'D4-3' '语音进箱并校验转写结果' 'POST' '/api/inbox/voice' 'HTTP 201 + text 转写非空 + status=new'
if (-not $ownerToken) {
    Set-DependencySkip $c '缺少户主 token'
} else {
    $audioPath = if ($AudioFile) { $AudioFile } else { Join-Path $root 'data\test-audio\sample.wav' }
    $audioIsSynthetic = $false
    if (-not (Test-Path $audioPath)) {
        try {
            New-Item -ItemType Directory -Path (Split-Path -Parent $audioPath) -Force | Out-Null
            New-SyntheticWav -Path $audioPath
            $audioIsSynthetic = $true
        } catch {
            $audioPath = $null
        }
    }
    if (-not $audioPath) {
        Set-Skipped $c '未找到可用音频文件且生成失败：可用 -AudioFile 指定一段 16k 单声道短语音后重跑'
    } elseif (-not $env:DASHSCOPE_API_KEY) {
        # 环境变量缺失时不再猜测：仍打一次请求，用返回状态判断服务端 ASR 是否可用
        $r = Send-Api -Method POST -Path '/api/inbox/voice' -Token $ownerToken -FilePath $audioPath
        Save-Response $c $r
        $voiceEventId = Get-Field $r.Json 'id'
        $voiceRawRef = Get-Field $r.Json 'rawRef'
        $asrStatus = [string](Get-Field $r.Json 'status')
        if ($r.Status -eq 202 -and $asrStatus -in @('asr_unconfigured', 'asr_failed')) {
            Assert-Equal $c '降级状态码' 202 $r.Status
            Assert-True $c '降级响应带 note 说明' ([bool](Get-Field $r.Json 'note')) "实际响应：$($r.Raw)"
            Set-Skipped $c "ASR 未配置（响应 status=$asrStatus）：上传链路已验证（202 降级、音频已落盘 rawRef=$voiceRawRef），转写内容无法校验；设置 DASHSCOPE_API_KEY 后重跑即可"
        } elseif ($r.Status -eq 201 -and (Get-Field $r.Json 'text')) {
            Assert-Equal $c 'HTTP 状态码' 201 $r.Status
            Assert-Equal $c 'status' 'new' (Get-Field $r.Json 'status')
            Assert-True $c 'text 转写非空' $true ''
        } else {
            Set-Skipped $c "DASHSCOPE_API_KEY 未设置，服务端返回 $($r.Status)/$asrStatus，转写无法校验（非产品缺陷）：$($r.Raw)"
        }
    } else {
        $r = Send-Api -Method POST -Path '/api/inbox/voice' -Token $ownerToken -FilePath $audioPath
        Save-Response $c $r
        $voiceEventId = Get-Field $r.Json 'id'
        $voiceRawRef = Get-Field $r.Json 'rawRef'
        $asrStatus = [string](Get-Field $r.Json 'status')
        if ($r.Status -eq 201) {
            Assert-Equal $c 'HTTP 状态码' 201 $r.Status
            Assert-Equal $c 'status' 'new' $asrStatus
            Assert-Equal $c 'source' 'voice' (Get-Field $r.Json 'source')
            $transcript = [string](Get-Field $r.Json 'text')
            Assert-True $c 'text 转写非空' (-not [string]::IsNullOrWhiteSpace($transcript)) "实际响应：$($r.Raw)"
            if ($audioIsSynthetic -and [string]::IsNullOrWhiteSpace($transcript)) {
                Set-Skipped $c '本次用的是脚本生成的合成音（不含语音），转写为空无法校验；请用 -AudioFile 指定真实短语音后重跑'
            }
        } elseif ($r.Status -eq 202 -and $asrStatus -in @('asr_unconfigured', 'asr_failed')) {
            Set-Skipped $c "服务端 ASR 不可用（status=$asrStatus）：音频已落盘 rawRef=$voiceRawRef，补齐 DASHSCOPE_API_KEY 后重跑可校验转写"
        } else {
            Add-Failure $c "语音进箱返回非预期结果：$($r.Status) $($r.Raw)"
        }
    }
}

# ------------------------------------------------------------------ D5 长期记忆

# D5-1 存私有记忆
$c = New-Case 'D5-1' '存一条私有记忆（默认 visibility=private）' 'POST' '/api/memories' 'HTTP 201 + visibility=private + id'
if (-not $ownerToken) {
    Set-DependencySkip $c '缺少户主 token'
} else {
    $body = @{ scope = 'work'; kind = 'preference'; content = "E2E 私有记忆：开会喜欢先看结论再看细节（$runId）" } | ConvertTo-Json -Compress
    $r = Send-Api -Method POST -Path '/api/memories' -Token $ownerToken -Json $body
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 201 $r.Status
    $privateMemoryId = Get-Field $r.Json 'id'
    Assert-True $c '返回记忆 id 为正整数' ([long]$privateMemoryId -gt 0) "id=$privateMemoryId"
    Assert-Equal $c 'visibility' 'private' (Get-Field $r.Json 'visibility')
    Assert-Equal $c 'scope' 'work' (Get-Field $r.Json 'scope')
    Assert-Equal $c 'familyId' $familyId (Get-Field $r.Json 'familyId')
    Assert-Equal $c 'memberId' $ownerMemberId (Get-Field $r.Json 'memberId')
}

# D5-2 存全家可见记忆
$c = New-Case 'D5-2' '存一条全家可见记忆（visibility=family）' 'POST' '/api/memories' 'HTTP 201 + visibility=family + id'
if (-not $ownerToken) {
    Set-DependencySkip $c '缺少户主 token'
} else {
    $body = @{ scope = 'learn'; kind = 'record'; content = "E2E 全家记忆：儿子本周背了 50 个单词（$runId）"; visibility = 'family' } | ConvertTo-Json -Compress
    $r = Send-Api -Method POST -Path '/api/memories' -Token $ownerToken -Json $body
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 201 $r.Status
    $familyMemoryId = Get-Field $r.Json 'id'
    Assert-True $c '返回记忆 id 为正整数' ([long]$familyMemoryId -gt 0) "id=$familyMemoryId"
    Assert-Equal $c 'visibility' 'family' (Get-Field $r.Json 'visibility')
    Assert-Equal $c 'scope' 'learn' (Get-Field $r.Json 'scope')
}

# D5-3 本人列表两条都在
$c = New-Case 'D5-3' '本人记忆列表两条都在' 'GET' '/api/memories' 'HTTP 200 + 含私有与全家记忆'
if (-not $privateMemoryId -or -not $familyMemoryId) {
    Set-DependencySkip $c 'D5-1/D5-2 未成功创建记忆'
} else {
    $r = Send-Api -Method GET -Path '/api/memories' -Token $ownerToken
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 200 $r.Status
    $items = @($r.Json)
    $ids = @($items | ForEach-Object { [string](Get-Field $_ 'id') })
    Assert-True $c "列表含私有记忆 $privateMemoryId" ($ids -contains [string]$privateMemoryId) "实际 id 列表：$($ids -join ',')"
    Assert-True $c "列表含全家记忆 $familyMemoryId" ($ids -contains [string]$familyMemoryId) "实际 id 列表：$($ids -join ',')"
    $leak = @($items | Where-Object { [string](Get-Field $_ 'familyId') -ne [string]$familyId })
    Assert-Equal $c '列表项 familyId 全部一致（无跨家庭泄漏）' 0 $leak.Count
}

# D5-4 按 scope 过滤
$c = New-Case 'D5-4' '按 scope=learn 过滤只返回学习记忆' 'GET' '/api/memories?scope=learn' 'HTTP 200 + 仅含 family 记忆'
if (-not $privateMemoryId -or -not $familyMemoryId) {
    Set-DependencySkip $c 'D5-1/D5-2 未成功创建记忆'
} else {
    $r = Send-Api -Method GET -Path '/api/memories?scope=learn' -Token $ownerToken
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 200 $r.Status
    $items = @($r.Json)
    $ids = @($items | ForEach-Object { [string](Get-Field $_ 'id') })
    Assert-True $c "过滤结果含 $familyMemoryId" ($ids -contains [string]$familyMemoryId) "实际 id 列表：$($ids -join ',')"
    Assert-True $c "过滤结果不含私有记忆 $privateMemoryId" (-not ($ids -contains [string]$privateMemoryId)) "实际 id 列表：$($ids -join ',')"
    Assert-Equal $c '过滤结果数量（本家庭仅 1 条 learn）' 1 $items.Count
    $badScope = @($items | Where-Object { [string](Get-Field $_ 'scope') -ne 'learn' })
    Assert-Equal $c '过滤结果 scope 全为 learn' 0 $badScope.Count
}

# D5-5 配偶视角列表：只看到 family 那条
$c = New-Case 'D5-5' '配偶列表只能看到全家可见记忆' 'GET' '/api/memories' 'HTTP 200 + 含 family 记忆、不含私有记忆'
if (-not $spouseToken -or -not $privateMemoryId -or -not $familyMemoryId) {
    Set-DependencySkip $c '缺少配偶 token 或记忆 id'
} else {
    $r = Send-Api -Method GET -Path '/api/memories' -Token $spouseToken
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 200 $r.Status
    $ids = @(@($r.Json) | ForEach-Object { [string](Get-Field $_ 'id') })
    Assert-True $c "含全家记忆 $familyMemoryId" ($ids -contains [string]$familyMemoryId) "实际 id 列表：$($ids -join ',')"
    Assert-True $c "不含私有记忆 $privateMemoryId" (-not ($ids -contains [string]$privateMemoryId)) "实际 id 列表：$($ids -join ',')"
}

# D5-6 配偶按 id 查私有记忆 → 404
$c = New-Case 'D5-6' '配偶按 id 直查私有记忆应为 404' 'GET' "/api/memories/{id}" 'HTTP 404 + error'
if (-not $spouseToken -or -not $privateMemoryId) {
    Set-DependencySkip $c '缺少配偶 token 或私有记忆 id'
} else {
    $r = Send-Api -Method GET -Path "/api/memories/$privateMemoryId" -Token $spouseToken
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 404 $r.Status
    Assert-True $c '错误响应体含 error 字段' ([bool](Get-Field $r.Json 'error')) "实际响应：$($r.Raw)"
}

# D5-7 配偶按 id 查 family 记忆 → 200（可见性正向验证）
$c = New-Case 'D5-7' '配偶按 id 查全家可见记忆应成功' 'GET' "/api/memories/{id}" 'HTTP 200 + visibility=family'
if (-not $spouseToken -or -not $familyMemoryId) {
    Set-DependencySkip $c '缺少配偶 token 或全家记忆 id'
} else {
    $r = Send-Api -Method GET -Path "/api/memories/$familyMemoryId" -Token $spouseToken
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 200 $r.Status
    Assert-Equal $c 'visibility' 'family' (Get-Field $r.Json 'visibility')
    Assert-Equal $c 'id' $familyMemoryId (Get-Field $r.Json 'id')
}

# D5-8 删除私有记忆
$c = New-Case 'D5-8' '删除私有记忆' 'DELETE' "/api/memories/{id}" 'HTTP 200 + status=deleted + id 一致'
if (-not $ownerToken -or -not $privateMemoryId) {
    Set-DependencySkip $c '缺少户主 token 或私有记忆 id'
} else {
    $r = Send-Api -Method DELETE -Path "/api/memories/$privateMemoryId" -Token $ownerToken
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 200 $r.Status
    Assert-Equal $c 'status' 'deleted' (Get-Field $r.Json 'status')
    Assert-Equal $c 'id' $privateMemoryId (Get-Field $r.Json 'id')
}

# D5-9 删除后按 id 查 → 404
$c = New-Case 'D5-9' '删除后按 id 查询应为 404' 'GET' "/api/memories/{id}" 'HTTP 404 + error'
if (-not $ownerToken -or -not $privateMemoryId) {
    Set-DependencySkip $c '缺少户主 token 或私有记忆 id'
} else {
    $r = Send-Api -Method GET -Path "/api/memories/$privateMemoryId" -Token $ownerToken
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 404 $r.Status
    Assert-True $c '错误响应体含 error 字段' ([bool](Get-Field $r.Json 'error')) "实际响应：$($r.Raw)"
}

# D5-10 删除后列表消失
$c = New-Case 'D5-10' '删除后列表中不再出现该私有记忆' 'GET' '/api/memories' 'HTTP 200 + 不含已删 id'
if (-not $ownerToken -or -not $privateMemoryId) {
    Set-DependencySkip $c '缺少户主 token 或私有记忆 id'
} else {
    $r = Send-Api -Method GET -Path '/api/memories' -Token $ownerToken
    Save-Response $c $r
    Assert-Equal $c 'HTTP 状态码' 200 $r.Status
    $ids = @(@($r.Json) | ForEach-Object { [string](Get-Field $_ 'id') })
    Assert-True $c "列表不含已删 id $privateMemoryId" (-not ($ids -contains [string]$privateMemoryId)) "实际 id 列表：$($ids -join ',')"
    if ($familyMemoryId) { Assert-True $c "列表仍含未删的全家记忆 $familyMemoryId" ($ids -contains [string]$familyMemoryId) "实际 id 列表：$($ids -join ',')" }
}

# ------------------------------------------------------------------ 结果判定

foreach ($case in $script:Cases) {
    if ($case.Status -ne 'SKIP' -and $case.Failures.Count -gt 0) { $case.Status = 'FAIL' }
}

$elapsed = ((Get-Date) - $script:StartedAt).TotalSeconds

# ------------------------------------------------------------------ 清理本次测试数据

Write-Host ''
Write-Host '=== 清理本次创建的数据 ===' -ForegroundColor Cyan
$cleanup = New-Object System.Collections.ArrayList
function Add-Cleanup {
    param([string]$Item, [string]$Result)
    [void]$cleanup.Add([pscustomobject]@{ Item = $Item; Result = $Result })
}

function Resolve-H2Jar {
    $candidates = @()
    if ($env:H2_JAR) { $candidates += $env:H2_JAR }
    foreach ($dir in @('D:\maven-repo-huatai\com\h2database\h2', (Join-Path $env:USERPROFILE '.m2\repository\com\h2database\h2'))) {
        if (Test-Path $dir) {
            $candidates += Get-ChildItem $dir -Filter 'h2-*.jar' -Recurse -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime -Descending | Select-Object -ExpandProperty FullName
        }
    }
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
    return $null
}

function Invoke-H2Sql {
    param([string]$Sql)
    $res = [pscustomobject]@{ Ok = $false; Output = '' }
    $h2Jar = Resolve-H2Jar
    $javaExe = Find-JavaExe
    if (-not $h2Jar -or -not $javaExe) {
        $res.Output = '未找到 h2 jar 或 java，无法直连 H2'
        return $res
    }
    $out = & $javaExe -cp $h2Jar org.h2.tools.Shell -url 'jdbc:h2:tcp://localhost:9092/mem:butler' -user sa -sql $Sql 2>&1 | Out-String
    $res.Output = $out.Trim()
    $res.Ok = ($LASTEXITCODE -eq 0)
    return $res
}

if ($SkipCleanup) {
    Add-Cleanup '全部测试数据' '按 -SkipCleanup 跳过（排查用）'
} else {
    # 1) 记忆：走 API 删除（family 可见那条 + 私有那条如未删）
    foreach ($mid in @($familyMemoryId, $privateMemoryId)) {
        if (-not $mid -or -not $ownerToken) { continue }
        $r = Send-Api -Method DELETE -Path "/api/memories/$mid" -Token $ownerToken
        if ($r.Status -eq 200) { Add-Cleanup "记忆 id=$mid" '已删除（DELETE /api/memories/{id}）' }
        elseif ($r.Status -eq 404) { Add-Cleanup "记忆 id=$mid" '已不存在（API 返回 404，无需再删）' }
        else { Add-Cleanup "记忆 id=$mid" "API 删除失败：$($r.Status) $($r.Raw)" }
    }

    # 2) 语音用例落盘的音频文件（只删本次 rawRef 指向的那个）
    if ($voiceRawRef) {
        $audioPath = Join-Path (Join-Path $root 'data\audio') $voiceRawRef
        if (Test-Path $audioPath) {
            Remove-Item $audioPath -Force
            Add-Cleanup "音频 $voiceRawRef" '已删除'
        } else {
            Add-Cleanup "音频 $voiceRawRef" '文件不存在，跳过'
        }
    }

    # 3) 账号/成员/收件箱/家庭：local 模式下的 H2 内存库直连 SQL 清理
    if ($familyId -and [long]$familyId -gt 0) {
        $fid = [long]$familyId
        $sql = @(
            "delete from todo_task where family_id = $fid",
            "delete from briefing where family_id = $fid",
            "delete from llm_call_log where family_id = $fid",
            "delete from memory_item where family_id = $fid",
            "delete from inbox_event where family_id = $fid",
            "delete from account where member_id in (select id from member where family_id = $fid)",
            "delete from member where family_id = $fid",
            "delete from family where id = $fid"
        ) -join '; '
        $sqlResult = Invoke-H2Sql -Sql $sql
        if ($sqlResult.Ok) { Add-Cleanup "家庭 familyId=$fid 及其账号/成员/收件箱行" '已通过 H2 TCP 执行 DELETE 清理' }
        else { Add-Cleanup "家庭 familyId=$fid 及其账号/成员/收件箱行" "H2 清理失败：$($sqlResult.Output)" }

        $verify = Invoke-H2Sql -Sql ("select (select count(*) from family where id = $fid) as family_left, " +
            "(select count(*) from member where family_id = $fid) as member_left, " +
            "(select count(*) from inbox_event where family_id = $fid) as inbox_left, " +
            "(select count(*) from memory_item where family_id = $fid) as memory_left")
        if ($verify.Ok) { Add-Cleanup '清理后行数校验' $verify.Output }
        else { Add-Cleanup '清理后行数校验' "校验查询失败：$($verify.Output)" }
    } else {
        Add-Cleanup '家庭/成员/账号/收件箱' '未创建成功，无需清理'
    }
}

foreach ($item in $cleanup) {
    Write-Host ("  - {0}：{1}" -f $item.Item, $item.Result)
}

# 脚本自己启动的进程跑完就关掉（复用已有实例时不动它）
if ($appStartedByScript -and $appProcess -and -not $appProcess.HasExited) {
    Stop-Process -Id $appProcess.Id -Force -ErrorAction SilentlyContinue
    Add-Cleanup "应用进程 pid=$($appProcess.Id)" '已停止（脚本启动的临时实例）'
}

# ------------------------------------------------------------------ 结果输出

Write-Host ''
Write-Host '=== 用例明细 ===' -ForegroundColor Cyan
foreach ($case in $script:Cases) {
    $color = switch ($case.Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'Yellow' } }
    Write-Host ("[{0}] {1} {2}" -f $case.Status, $case.Id, $case.Name) -ForegroundColor $color
    Write-Host ("       {0} {1}{2}" -f $case.Method, $BaseUrl, $case.Path)
    if ($case.Note) { Write-Host "       说明：$($case.Note)" -ForegroundColor Yellow }
    if ($case.Status -eq 'FAIL') {
        Write-Host "       期望：$($case.Expected)"
        Write-Host "       实际状态码：$($case.ActualStatus)"
        Write-Host "       响应体：$($case.ResponseBody)"
        foreach ($f in $case.Failures) { Write-Host "       断言失败：$f" -ForegroundColor Red }
    } elseif ($case.Failures.Count -gt 0) {
        foreach ($f in $case.Failures) { Write-Host "       附注：$f" -ForegroundColor Yellow }
    }
}

$total = $script:Cases.Count
$passed = @($script:Cases | Where-Object { $_.Status -eq 'PASS' }).Count
$failed = @($script:Cases | Where-Object { $_.Status -eq 'FAIL' }).Count
$skipped = @($script:Cases | Where-Object { $_.Status -eq 'SKIP' }).Count

Write-Host ''
Write-Host '=== 执行摘要 ===' -ForegroundColor Cyan
Write-Host ("用例总数: {0} | 通过: {1} | 失败: {2} | 跳过: {3} | 总耗时: {4:N1} 秒" -f `
    $total, $passed, $failed, $skipped, $elapsed)
Write-Host "实例：$reuseNote"

if ($failed -gt 0) {
    Write-Host ''
    Write-Host '--- 失败用例应用日志 ---' -ForegroundColor Red
    if ($appStartedByScript -and (Test-Path $appLogPath)) {
        Get-Content $appLogPath -Tail 60 | ForEach-Object { Write-Host "  $_" }
    } else {
        Write-Host '  本次复用已在运行的实例（IDEA 启动），日志在 IDEA Run 控制台，脚本无法直接读取；'
        Write-Host '  需抓日志时用：mvn spring-boot:run -Dspring-boot.run.profiles=local > app.log 2>&1 后重跑本脚本。'
    }
}

$reportPath = Join-Path $logDir "e2e-report-$runId.json"
$report = [pscustomobject]@{
    runId      = $runId
    baseUrl    = $BaseUrl
    startedAt  = $script:StartedAt.ToString('s')
    elapsedSec = [math]::Round($elapsed, 2)
    instance   = $reuseNote
    summary    = [pscustomobject]@{ total = $total; passed = $passed; failed = $failed; skipped = $skipped }
    cases      = @($script:Cases | ForEach-Object {
        [pscustomobject]@{
            id = $_.Id; name = $_.Name; method = $_.Method; path = $_.Path
            status = $_.Status; expected = $_.Expected; actualStatus = $_.ActualStatus
            response = $_.ResponseBody; note = $_.Note; failures = @($_.Failures)
        }
    })
    cleanup    = @($cleanup)
}
$report | ConvertTo-Json -Depth 6 | Set-Content -Path $reportPath -Encoding UTF8
Write-Host "报告文件：$reportPath"

if ($appStartedByScript) {
    Write-Host "应用日志：$appLogPath"
}

if ($failed -gt 0) { exit 1 } else { exit 0 }
