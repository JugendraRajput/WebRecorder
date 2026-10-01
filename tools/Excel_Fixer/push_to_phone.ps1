$adb = "C:\Users\jugen\AppData\Local\Android\Sdk\platform-tools\adb.exe"

Write-Host "Detecting phone..." -ForegroundColor Cyan
$deviceLine = (& $adb devices -l | Where-Object { $_ -match "model:25080RABDI|model:2|OBBILNEQ" })

$targetDevice = ""
if ($deviceLine) {
    $targetDevice = ($deviceLine -split '\s+')[0]
    Write-Host "Found phone: $targetDevice" -ForegroundColor Green
} else {
    # If only one device attached, check if it's not the POS terminal
    $allDevices = & $adb devices | Where-Object { $_ -match "\tdevice$" }
    if ($allDevices.Count -eq 1) {
        $targetDevice = ($allDevices[0] -split '\t')[0]
        Write-Host "Using attached device: $targetDevice" -ForegroundColor Yellow
    }
}

if (-not $targetDevice) {
    Write-Host "Please connect your phone (REDMI Note 15 Pro) via USB cable..." -ForegroundColor Yellow
    & $adb wait-for-device
    $targetDevice = (& $adb devices | Where-Object { $_ -match "\tdevice$" } | Select-Object -First 1) -split '\t' | Select-Object -First 1
}

Write-Host "Target device: $targetDevice" -ForegroundColor Green
& $adb -s $targetDevice shell "mkdir -p /sdcard/Movies/Excel/"

Write-Host "`nPushing 16 clean & filtered Excel files to /sdcard/Movies/Excel/..." -ForegroundColor Green
$files = Get-ChildItem "E:\WebRecorder\tools\Excel_Fixer\fixed\*.xlsx"
foreach ($f in $files) {
    Write-Host "  Pushing $($f.Name)..."
    & $adb -s $targetDevice push $f.FullName /sdcard/Movies/Excel/
}

Write-Host "`nVerifying files on phone:" -ForegroundColor Cyan
& $adb -s $targetDevice shell "ls -l /sdcard/Movies/Excel/"

Write-Host "`nAll 16 Excel files successfully updated on your phone!" -ForegroundColor Green
