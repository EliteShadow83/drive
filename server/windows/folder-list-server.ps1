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

  if ($req.Url.AbsolutePath -eq "/list") {
    $path = $req.QueryString["path"]
    if ([string]::IsNullOrWhiteSpace($path)) { $path = "." }

    $full = Join-Path $Root $path
    if (Test-Path $full) {
      $items = Get-ChildItem $full | Select-Object -ExpandProperty Name
      $body = [Text.Encoding]::UTF8.GetBytes(($items -join "`n"))
      $res.StatusCode = 200
      $res.ContentType = "text/plain; charset=utf-8"
      $res.OutputStream.Write($body, 0, $body.Length)
    } else {
      $res.StatusCode = 404
      $body = [Text.Encoding]::UTF8.GetBytes("Folder not found")
      $res.OutputStream.Write($body, 0, $body.Length)
    }
  } else {
    $res.StatusCode = 404
  }

  $res.Close()
}
