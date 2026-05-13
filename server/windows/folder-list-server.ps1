param(
  [string]$Root = "C:\Shared",
  [int]$Port = 8080
)

$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://+:$Port/")
$listener.Start()
Write-Host "Server running on port $Port"
Write-Host "Root folder: $Root"

while ($listener.IsListening) {
  $context = $listener.GetContext()
  $req = $context.Request
  $res = $context.Response

  $path = $req.QueryString["path"]
  if ([string]::IsNullOrWhiteSpace($path)) { $path = "." }
  $folder = Join-Path $Root $path

  switch ($req.Url.AbsolutePath) {
    "/list" {
      if (Test-Path $folder) {
        $items = Get-ChildItem $folder | Select-Object -ExpandProperty Name
        $body = [Text.Encoding]::UTF8.GetBytes(($items -join "`n"))
        $res.StatusCode = 200
        $res.ContentType = "text/plain; charset=utf-8"
        $res.OutputStream.Write($body, 0, $body.Length)
      } else {
        $res.StatusCode = 404
      }
    }
    "/download" {
      $name = $req.QueryString["name"]
      $target = Join-Path $folder $name
      if ((Test-Path $target) -and -not (Get-Item $target).PSIsContainer) {
        $bytes = [System.IO.File]::ReadAllBytes($target)
        $res.StatusCode = 200
        $res.ContentType = "application/octet-stream"
        $res.OutputStream.Write($bytes, 0, $bytes.Length)
      } else {
        $res.StatusCode = 404
      }
    }
    "/upload" {
      $name = $req.QueryString["name"]
      if (-not (Test-Path $folder)) { New-Item -ItemType Directory -Path $folder | Out-Null }
      $target = Join-Path $folder $name
      $ms = New-Object System.IO.MemoryStream
      $req.InputStream.CopyTo($ms)
      [System.IO.File]::WriteAllBytes($target, $ms.ToArray())
      $res.StatusCode = 200
    }
    "/delete" {
      $name = $req.QueryString["name"]
      $target = Join-Path $folder $name
      if (Test-Path $target) {
        Remove-Item -Path $target -Force
        $res.StatusCode = 200
      } else {
        $res.StatusCode = 404
      }
    }
    default {
      $res.StatusCode = 404
    }
  }

  $res.Close()
}
