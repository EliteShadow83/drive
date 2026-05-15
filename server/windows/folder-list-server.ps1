param(
  [string]$Root = "C:\Shared",
  [int]$Port = 8080
)

$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://+:$Port/")
$listener.Start()
Write-Host "WinDrive server running on port $Port" -ForegroundColor Green
Write-Host "Shared root: $Root" -ForegroundColor Green
Write-Host "Open in browser: http://localhost:$Port/" -ForegroundColor Cyan

function Write-RequestLog {
  param(
    [string]$Client,
    [string]$Method,
    [string]$Path,
    [int]$Status
  )

  $time = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
  Write-Host "[$time] $Client -> $Method $Path => $Status"
}

while ($listener.IsListening) {
  $context = $listener.GetContext()
  $req = $context.Request
  $res = $context.Response

  $client = $req.RemoteEndPoint.ToString()
  $path = $req.QueryString["path"]
  if ([string]::IsNullOrWhiteSpace($path)) { $path = "." }
  $folder = Join-Path $Root $path

  $status = 500

  try {
    switch ($req.Url.AbsolutePath) {
      "/" {
        $html = @"
<html>
  <head><title>WinDrive Server</title></head>
  <body style='font-family:Segoe UI, sans-serif;'>
    <h2>WinDrive Server</h2>
    <p>Server is running.</p>
    <ul>
      <li><a href='/list?path=.'>/list?path=.</a></li>
      <li>/download?path=.&name=filename.txt</li>
      <li>POST /upload?path=.&name=filename.txt</li>
      <li>POST /delete?path=.&name=filename.txt</li>
    </ul>
  </body>
</html>
"@
        $body = [Text.Encoding]::UTF8.GetBytes($html)
        $res.ContentType = "text/html; charset=utf-8"
        $res.StatusCode = 200
        $res.OutputStream.Write($body, 0, $body.Length)
        $status = 200
      }
      "/list" {
        if (Test-Path $folder) {
          $items = Get-ChildItem $folder | Select-Object -ExpandProperty Name
          $body = [Text.Encoding]::UTF8.GetBytes(($items -join "`n"))
          $res.StatusCode = 200
          $res.ContentType = "text/plain; charset=utf-8"
          $res.OutputStream.Write($body, 0, $body.Length)
          $status = 200
        } else {
          $res.StatusCode = 404
          $status = 404
        }
      }
      "/download" {
        $name = $req.QueryString["name"]
        if ([string]::IsNullOrWhiteSpace($name)) { $res.StatusCode = 400; $status = 400; break }
        $target = Join-Path $folder $name
        if ((Test-Path $target) -and -not (Get-Item $target).PSIsContainer) {
          $bytes = [System.IO.File]::ReadAllBytes($target)
          $res.StatusCode = 200
          $res.ContentType = "application/octet-stream"
          $res.AddHeader("Content-Disposition", "attachment; filename=`"$name`"")
          $res.OutputStream.Write($bytes, 0, $bytes.Length)
          $status = 200
        } else {
          $res.StatusCode = 404
          $status = 404
        }
      }
      "/upload" {
        $name = $req.QueryString["name"]
        if ([string]::IsNullOrWhiteSpace($name)) { $res.StatusCode = 400; $status = 400; break }
        if (-not (Test-Path $folder)) { New-Item -ItemType Directory -Path $folder | Out-Null }
        $target = Join-Path $folder $name
        $ms = New-Object System.IO.MemoryStream
        $req.InputStream.CopyTo($ms)
        [System.IO.File]::WriteAllBytes($target, $ms.ToArray())
        $res.StatusCode = 200
        $status = 200
      }
      "/delete" {
        $name = $req.QueryString["name"]
        if ([string]::IsNullOrWhiteSpace($name)) { $res.StatusCode = 400; $status = 400; break }
        $target = Join-Path $folder $name
        if (Test-Path $target) {
          Remove-Item -Path $target -Force
          $res.StatusCode = 200
          $status = 200
        } else {
          $res.StatusCode = 404
          $status = 404
        }
      }
      default {
        $res.StatusCode = 404
        $status = 404
      }
    }
  }
  catch {
    $res.StatusCode = 500
    $status = 500
    $errorBody = [Text.Encoding]::UTF8.GetBytes("Server error: $($_.Exception.Message)")
    $res.OutputStream.Write($errorBody, 0, $errorBody.Length)
  }
  finally {
    Write-RequestLog -Client $client -Method $req.HttpMethod -Path $req.RawUrl -Status $status
    $res.Close()
  }
}
