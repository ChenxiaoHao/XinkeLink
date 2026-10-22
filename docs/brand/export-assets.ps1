# Exports approved PNG masters without changing their shapes or colors.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$destination = Join-Path $repositoryRoot 'apps/miniprogram/miniprogram/assets/brand'
New-Item -ItemType Directory -Force $destination | Out-Null

function Export-BrandPng([string]$Source, [string]$Name, [int]$Size, [bool]$CenterAlpha) {
  $sourceImage = [System.Drawing.Bitmap]::new($Source)
  $output = [System.Drawing.Bitmap]::new($Size, $Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $graphics = [System.Drawing.Graphics]::FromImage($output)
  try {
    $sourceRect = [System.Drawing.Rectangle]::new(0, 0, $sourceImage.Width, $sourceImage.Height)
    if ($CenterAlpha) {
      $left = $sourceImage.Width; $top = $sourceImage.Height; $right = -1; $bottom = -1
      for ($y = 0; $y -lt $sourceImage.Height; $y++) {
        for ($x = 0; $x -lt $sourceImage.Width; $x++) {
          # Ignore practically invisible alpha noise when measuring the mark;
          # its visible contour stays inside the resulting padded rectangle.
          if ($sourceImage.GetPixel($x, $y).A -le 16) { continue }
          $left = [Math]::Min($left, $x); $right = [Math]::Max($right, $x)
          $top = [Math]::Min($top, $y); $bottom = [Math]::Max($bottom, $y)
        }
      }
      if ($right -lt $left) { throw 'The mark master contains no visible pixels.' }
      $sourceRect = [System.Drawing.Rectangle]::new($left, $top, $right - $left + 1, $bottom - $top + 1)
    }
    # Uniform scaling preserves proportions. The transparent mark gets 8% safe
    # padding on every side; the square master already includes its own padding.
    $available = if ($CenterAlpha) { $Size * 0.84 } else { $Size }
    $scale = $available / [Math]::Max($sourceRect.Width, $sourceRect.Height)
    $width = [int][Math]::Round($sourceRect.Width * $scale)
    $height = [int][Math]::Round($sourceRect.Height * $scale)
    $targetRect = [System.Drawing.Rectangle]::new([int](($Size - $width) / 2), [int](($Size - $height) / 2), $width, $height)
    if ($CenterAlpha) {
      $graphics.Clear([System.Drawing.Color]::Transparent)
      $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
    } else {
      # Bicubic sampling at the bitmap edge must not create translucent app-icon
      # corners. Composite onto the approved opaque brand background.
      $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#6489B8'))
      $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceOver
    }
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.DrawImage($sourceImage, $targetRect, $sourceRect, [System.Drawing.GraphicsUnit]::Pixel)
    $path = Join-Path $destination $Name
    $output.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    Write-Output ($Name + ': ' + $Size + 'x' + $Size + ', ' + (Get-Item -LiteralPath $path).Length + ' bytes')
  } finally {
    $graphics.Dispose(); $output.Dispose(); $sourceImage.Dispose()
  }
}

Export-BrandPng (Join-Path $PSScriptRoot 'xinkelink-mark-master.png') 'xinkelink-mark.png' 256 $true
Export-BrandPng (Join-Path $PSScriptRoot 'xinkelink-square-master.png') 'xinkelink-square.png' 512 $false
