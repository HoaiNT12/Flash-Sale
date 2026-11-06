#requires -Version 5.1
# Shared helpers for Windows PowerShell 5.1 and PowerShell 7. No modules required.

function ConvertFrom-CompatJson {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$InputObject,
        [switch]$AsHashtable
    )
    # A wrapper keeps root []/[one]/[null] intact in the legacy JSON cmdlet.
    $wrapper = ConvertFrom-Json -InputObject ('{"value":' + $InputObject + '}') -ErrorAction Stop
    $value = $wrapper.value
    if (-not $AsHashtable) { return ,$value }

    function Convert-JsonNode($Node) {
        if ($null -eq $Node) { return $null }
        if ($Node -is [System.Management.Automation.PSCustomObject]) {
            $dictionary = @{}
            foreach ($property in $Node.PSObject.Properties) {
                $dictionary[$property.Name] = Convert-JsonNode $property.Value
            }
            return ,$dictionary
        }
        if ($Node -is [System.Array]) {
            $items = [Collections.Generic.List[object]]::new()
            foreach ($item in $Node) { $items.Add((Convert-JsonNode $item)) }
            return ,$items.ToArray()
        }
        return $Node
    }
    return ,(Convert-JsonNode $value)
}

function Invoke-CompatWebRequest {
    [CmdletBinding()]
    param(
        [string]$Method = 'GET',
        [Parameter(Mandatory = $true)][string]$Uri,
        [System.Collections.IDictionary]$Headers = @{},
        [string]$ContentType,
        $Body,
        [ValidateRange(1, 600)][int]$TimeoutSec = 10
    )

    function Copy-ResponseHeaders($Source, [hashtable]$Destination) {
        if ($null -eq $Source) { return }
        if ($Source -is [Collections.Specialized.NameValueCollection]) {
            foreach ($name in $Source.AllKeys) { $Destination[$name] = [string]$Source[$name] }
        } elseif ($Source -is [Collections.IDictionary]) {
            foreach ($name in $Source.Keys) {
                $Destination[[string]$name] = [string]::Join(', ', [string[]]@($Source[$name]))
            }
        } else {
            foreach ($header in $Source) {
                $Destination[[string]$header.Key] = [string]::Join(', ', [string[]]@($header.Value))
            }
        }
    }

    $parameters = @{ Method = $Method; Uri = $Uri; Headers = $Headers; TimeoutSec = $TimeoutSec; ErrorAction = 'Stop' }
    if ($PSVersionTable.PSVersion.Major -lt 6) { $parameters.UseBasicParsing = $true }
    if ($PSBoundParameters.ContainsKey('ContentType')) { $parameters.ContentType = $ContentType }
    if ($PSBoundParameters.ContainsKey('Body')) {
        # JSON has the same UTF-8 wire representation in both PowerShell editions.
        if ($Body -is [string] -and $ContentType -match '^application/json') {
            $parameters.Body = [Text.Encoding]::UTF8.GetBytes($Body)
        } else { $parameters.Body = $Body }
    }

    try {
        $response = Invoke-WebRequest @parameters
        $responseHeaders = @{}
        Copy-ResponseHeaders $response.Headers $responseHeaders
        return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Content = [string]$response.Content; Headers = $responseHeaders }
    } catch {
        $failure = $_
        $response = $null
        if ($null -ne $failure.Exception.PSObject.Properties['Response']) { $response = $failure.Exception.Response }
        if ($null -eq $response) { throw }
        # HTTP error responses remain HTTP responses; connection/timeouts still throw.
        $status = [int]$response.StatusCode
        if ($status -lt 100 -or $status -gt 599) { throw }
        $responseHeaders = @{}
        Copy-ResponseHeaders $response.Headers $responseHeaders
        $content = ''
        try {
            if ($null -ne $response.PSObject.Properties['Content']) {
                Copy-ResponseHeaders $response.Content.Headers $responseHeaders
                $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            } else {
                $stream = $response.GetResponseStream()
                if ($null -ne $stream) {
                    $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $true)
                    try { $content = $reader.ReadToEnd() } finally { $reader.Dispose() }
                }
            }
        } catch {
            # Windows PowerShell may have consumed the response stream already.
            $content = ''
        }
        if ([string]::IsNullOrEmpty($content) -and $null -ne $failure.ErrorDetails) {
            $content = [string]$failure.ErrorDetails.Message
        }
        if ($null -ne $response.PSObject.Methods['Dispose']) { $response.Dispose() }
        return [pscustomobject]@{ StatusCode = $status; Content = [string]$content; Headers = $responseHeaders }
    }
}

function Invoke-ParallelWork {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$InputObject,
        [Parameter(Mandatory = $true)][scriptblock]$ScriptBlock,
        [object[]]$ArgumentList = @(),
        [ValidateRange(1, 128)][int]$ThrottleLimit = 8
    )

    if ($InputObject.Count -eq 0) { return }
    # Workers are isolated: pass shared values explicitly through ArgumentList.
    $initialState = [System.Management.Automation.Runspaces.InitialSessionState]::CreateDefault()
    $initialState.Variables.Add([System.Management.Automation.Runspaces.SessionStateVariableEntry]::new(
        'ErrorActionPreference', [System.Management.Automation.ActionPreference]::Stop, 'Propagate worker errors'))
    $pool = [runspacefactory]::CreateRunspacePool(1, $ThrottleLimit, $initialState, $Host)
    $jobs = [Collections.Generic.List[object]]::new()
    try {
        $pool.Open()
        foreach ($item in $InputObject) {
            $worker = [powershell]::Create()
            $worker.RunspacePool = $pool
            $job = @{ Worker = $worker; Handle = $null }
            $jobs.Add($job)
            [void]$worker.AddScript($ScriptBlock.ToString()).AddArgument($item)
            foreach ($argument in $ArgumentList) { [void]$worker.AddArgument($argument) }
            $job.Handle = $worker.BeginInvoke()
        }
        foreach ($job in $jobs) {
            $results = $job.Worker.EndInvoke($job.Handle)
            if ($job.Worker.Streams.Error.Count -gt 0) { throw $job.Worker.Streams.Error[0] }
            foreach ($result in $results) { $PSCmdlet.WriteObject($result, $false) }
        }
    } finally {
        foreach ($job in $jobs) {
            try {
                if ($job.Worker.InvocationStateInfo.State -in @('Running', 'Stopping')) { $job.Worker.Stop() }
            } finally { $job.Worker.Dispose() }
        }
        $pool.Close()
        $pool.Dispose()
    }
}
