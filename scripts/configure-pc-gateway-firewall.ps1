param(
    [int]$Port = 8080,
    [int]$DiscoveryPort = 42888,
    [switch]$EnableLanDiscovery,
    [string]$RulePrefix = 'Relay PC Gateway'
)

$ErrorActionPreference = 'Stop'

# Requires elevation. Limits exposure to the Private profile (emergency LAN).
$rules = @(
    @{ Name = "$RulePrefix HTTP"; Protocol = 'TCP'; LocalPort = $Port }
)
if ($EnableLanDiscovery) {
    $rules += @{ Name = "$RulePrefix LAN Discovery"; Protocol = 'UDP'; LocalPort = $DiscoveryPort }
} else {
    # Remove the legacy discovery opening when moving an existing installation to the safe
    # production default. No UDP listener is needed unless explicitly enabled.
    Get-NetFirewallRule -DisplayName "$RulePrefix LAN Discovery" -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule -ErrorAction SilentlyContinue
}

foreach ($rule in $rules) {
    $existing = Get-NetFirewallRule -DisplayName $rule.Name -ErrorAction SilentlyContinue
    if ($existing) {
        Remove-NetFirewallRule -DisplayName $rule.Name
    }
    New-NetFirewallRule `
        -DisplayName $rule.Name `
        -Direction Inbound `
        -Action Allow `
        -Protocol $rule.Protocol `
        -LocalPort $rule.LocalPort `
        -Profile Private `
        -Description 'Relay explicitly configured closed-network Gateway. Do not enable on Public profile.' | Out-Null
    Write-Output "Allowed inbound $($rule.Protocol)/$($rule.LocalPort) on Private profile ($($rule.Name))"
}

Write-Output "Ensure this PC's network profile is Private: Get-NetConnectionProfile"
Write-Output "Do not open these ports on Public / guest Wi-Fi. This rule does not replace an approved network boundary."
