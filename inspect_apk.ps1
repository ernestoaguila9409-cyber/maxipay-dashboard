Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::OpenRead("app\libs\corebridge-service.apk")
foreach ($e in $z.Entries) {
  if ($e.FullName -match "print|aidl|Printer") {
    Write-Output $e.FullName
  }
}
$z.Dispose()
