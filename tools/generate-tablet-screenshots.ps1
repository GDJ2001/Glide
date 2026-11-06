# tools/generate-tablet-screenshots.ps1
# High-fidelity tablet screenshot generator for Glide v1.0.0 Play Store listing.
# Produces 24-bit RGB PNG screenshots (no alpha) for 7-inch (1920x1200) and 10-inch (2560x1600).

[CmdletBinding()]
param(
    [string]$OutputDir7Inch = "",
    [string]$OutputDir10Inch = "",
    [string]$OutputDirGeneralTablet = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path "$PSScriptRoot\..").Path

if (-not $OutputDir7Inch) {
    $OutputDir7Inch = "$projectRoot\doc\release\store_assets\screenshots\tablet_7inch"
}
if (-not $OutputDir10Inch) {
    $OutputDir10Inch = "$projectRoot\doc\release\store_assets\screenshots\tablet_10inch"
}
if (-not $OutputDirGeneralTablet) {
    $OutputDirGeneralTablet = "$projectRoot\doc\release\store_assets\screenshots\tablet"
}

if (-not (Test-Path $OutputDir7Inch)) { New-Item -ItemType Directory -Path $OutputDir7Inch -Force | Out-Null }
if (-not (Test-Path $OutputDir10Inch)) { New-Item -ItemType Directory -Path $OutputDir10Inch -Force | Out-Null }
if (-not (Test-Path $OutputDirGeneralTablet)) { New-Item -ItemType Directory -Path $OutputDirGeneralTablet -Force | Out-Null }

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "   Glide Play Store Tablet Screenshot Generator             " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "7-inch target:  $OutputDir7Inch (1920x1200)" -ForegroundColor Gray
Write-Host "10-inch target: $OutputDir10Inch (2560x1600)" -ForegroundColor Gray
Write-Host "Tablet legacy:  $OutputDirGeneralTablet (1920x1200)" -ForegroundColor Gray

$csharpCode = @'
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;
using System.IO;

public static class TabletScreenshotComposer {
    // Canonical color tokens from dev.glide.designsystem.Color.kt
    public static readonly Color BgLight = ColorTranslator.FromHtml("#F8F9FF");
    public static readonly Color OnBgLight = ColorTranslator.FromHtml("#151B38");
    public static readonly Color SurfaceLight = ColorTranslator.FromHtml("#F8F9FF");
    public static readonly Color SurfaceLowLight = ColorTranslator.FromHtml("#F0F2FA");
    public static readonly Color SurfaceHighLight = ColorTranslator.FromHtml("#E6EAF5");
    public static readonly Color PrimaryLight = ColorTranslator.FromHtml("#375BEE");
    public static readonly Color OnPrimaryLight = ColorTranslator.FromHtml("#FFFFFF");
    public static readonly Color PrimaryContainerLight = ColorTranslator.FromHtml("#E0E5FF");
    public static readonly Color OnPrimaryContainerLight = ColorTranslator.FromHtml("#10256E");
    public static readonly Color SecondaryLight = ColorTranslator.FromHtml("#465475");
    public static readonly Color SecondaryContainerLight = ColorTranslator.FromHtml("#DEE5FA");
    public static readonly Color OnSecondaryContainerLight = ColorTranslator.FromHtml("#151B38");
    public static readonly Color OutlineLight = ColorTranslator.FromHtml("#69738C");
    public static readonly Color OutlineVariantLight = ColorTranslator.FromHtml("#C5CDDF");
    public static readonly Color SuccessLight = ColorTranslator.FromHtml("#126C45");
    public static readonly Color SuccessContainerLight = ColorTranslator.FromHtml("#D4F7E2");
    public static readonly Color OnSuccessContainerLight = ColorTranslator.FromHtml("#003921");
    public static readonly Color ErrorLight = ColorTranslator.FromHtml("#BA1A1A");
    public static readonly Color ErrorContainerLight = ColorTranslator.FromHtml("#FFDAD6");
    public static readonly Color OnErrorContainerLight = ColorTranslator.FromHtml("#410002");
    public static readonly Color WarningLight = ColorTranslator.FromHtml("#795400");
    public static readonly Color WarningContainerLight = ColorTranslator.FromHtml("#FFE8A3");
    public static readonly Color OnWarningContainerLight = ColorTranslator.FromHtml("#402D00");
    public static readonly Color BrandCyanAccent = ColorTranslator.FromHtml("#22CBE5");

    private static GraphicsPath CreateRoundedPath(RectangleF rect, float radius) {
        GraphicsPath path = new GraphicsPath();
        float d = Math.Max(1f, radius * 2);
        if (d > rect.Width) d = rect.Width;
        if (d > rect.Height) d = rect.Height;
        path.AddArc(rect.X, rect.Y, d, d, 180, 90);
        path.AddArc(rect.Right - d, rect.Y, d, d, 270, 90);
        path.AddArc(rect.Right - d, rect.Bottom - d, d, d, 0, 90);
        path.AddArc(rect.X, rect.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    public static void FillRoundedRect(Graphics g, Brush brush, RectangleF rect, float radius) {
        using (GraphicsPath path = CreateRoundedPath(rect, radius)) {
            g.FillPath(brush, path);
        }
    }

    public static void DrawRoundedRect(Graphics g, Pen pen, RectangleF rect, float radius) {
        using (GraphicsPath path = CreateRoundedPath(rect, radius)) {
            g.DrawPath(pen, path);
        }
    }

    public static void DrawCard(Graphics g, RectangleF rect, Color bgColor, Color? borderColor = null, float radius = 16f, bool shadow = true) {
        if (shadow) {
            RectangleF shadowRect = new RectangleF(rect.X + 2, rect.Y + 3, rect.Width, rect.Height);
            using (SolidBrush sb = new SolidBrush(Color.FromArgb(14, 21, 27, 56))) {
                FillRoundedRect(g, sb, shadowRect, radius);
            }
        }
        using (SolidBrush bb = new SolidBrush(bgColor)) {
            FillRoundedRect(g, bb, rect, radius);
        }
        if (borderColor.HasValue) {
            using (Pen p = new Pen(borderColor.Value, 1.2f)) {
                DrawRoundedRect(g, p, rect, radius);
            }
        }
    }

    public static void DrawStatusBar(Graphics g, float width, float scale) {
        using (Font font = new Font("Segoe UI", 12f * scale, FontStyle.Bold))
        using (SolidBrush brush = new SolidBrush(OnBgLight)) {
            g.DrawString("9:41", font, brush, 24f * scale, 12f * scale);
        }

        float rightX = width - 110f * scale;
        float y = 14f * scale;

        // Cellular signal
        using (SolidBrush b = new SolidBrush(OnBgLight)) {
            for (int i = 0; i < 4; i++) {
                float barH = (5f + i * 3f) * scale;
                g.FillRectangle(b, rightX + (i * 5f) * scale, y + (14f * scale - barH), 3f * scale, barH);
            }
        }

        // Wi-Fi arcs
        rightX += 30f * scale;
        using (Pen pen = new Pen(OnBgLight, 2f * scale)) {
            g.DrawArc(pen, rightX, y + 2f * scale, 16f * scale, 14f * scale, 220, 100);
            g.DrawArc(pen, rightX + 3f * scale, y + 6f * scale, 10f * scale, 9f * scale, 220, 100);
        }
        using (SolidBrush b = new SolidBrush(OnBgLight)) {
            g.FillEllipse(b, rightX + 6.5f * scale, y + 11.5f * scale, 3f * scale, 3f * scale);
        }

        // Battery
        rightX += 28f * scale;
        using (Pen pen = new Pen(OnBgLight, 1.8f * scale)) {
            using (GraphicsPath bp = CreateRoundedPath(new RectangleF(rightX, y + 2f * scale, 24f * scale, 12f * scale), 3f * scale)) {
                g.DrawPath(pen, bp);
            }
        }
        using (SolidBrush b = new SolidBrush(OnBgLight)) {
            g.FillRectangle(b, rightX + 25f * scale, y + 5.5f * scale, 2f * scale, 5f * scale);
            g.FillRectangle(b, rightX + 2.5f * scale, y + 4f * scale, 19f * scale, 8f * scale);
        }
    }

    public static void DrawGestureBar(Graphics g, float width, float height, float scale) {
        float barW = 260f * scale;
        float barH = 5f * scale;
        float x = (width - barW) / 2f;
        float y = height - 16f * scale;
        using (SolidBrush b = new SolidBrush(OutlineVariantLight)) {
            FillRoundedRect(g, b, new RectangleF(x, y, barW, barH), 2.5f * scale);
        }
    }

    public static void DrawNavRail(Graphics g, float height, float scale, int activeIndex, Image logo) {
        float railW = 180f * scale;
        using (SolidBrush rb = new SolidBrush(SurfaceLowLight)) {
            g.FillRectangle(rb, 0, 0, railW, height);
        }
        using (Pen dp = new Pen(SurfaceHighLight, 1.5f)) {
            g.DrawLine(dp, railW, 0, railW, height);
        }

        // Header logo
        float logoSize = 44f * scale;
        if (logo != null) {
            g.DrawImage(logo, (railW - logoSize) / 2f, 40f * scale, logoSize, logoSize);
        }

        string[] labels = new string[] { "Home", "History", "Settings" };
        float startY = 125f * scale;
        float itemH = 80f * scale;

        for (int i = 0; i < 3; i++) {
            float curY = startY + (i * itemH);
            bool isActive = (i == activeIndex);

            if (isActive) {
                float pillW = 76f * scale;
                float pillH = 38f * scale;
                RectangleF pillRect = new RectangleF((railW - pillW) / 2f, curY, pillW, pillH);
                using (SolidBrush pb = new SolidBrush(PrimaryContainerLight)) {
                    FillRoundedRect(g, pb, pillRect, 19f * scale);
                }
            }

            Color iconColor = isActive ? PrimaryLight : SecondaryLight;
            float iconX = railW / 2f;
            float iconY = curY + 19f * scale;

            using (SolidBrush ib = new SolidBrush(iconColor))
            using (Pen ip = new Pen(iconColor, 2f * scale)) {
                if (i == 0) {
                    PointF[] roof = new PointF[] {
                        new PointF(iconX, iconY - 10f * scale),
                        new PointF(iconX + 11f * scale, iconY),
                        new PointF(iconX + 8f * scale, iconY),
                        new PointF(iconX + 8f * scale, iconY + 9f * scale),
                        new PointF(iconX - 8f * scale, iconY + 9f * scale),
                        new PointF(iconX - 8f * scale, iconY),
                        new PointF(iconX - 11f * scale, iconY)
                    };
                    g.FillPolygon(ib, roof);
                } else if (i == 1) {
                    g.DrawEllipse(ip, iconX - 9f * scale, iconY - 9f * scale, 18f * scale, 18f * scale);
                    g.DrawLine(ip, iconX, iconY - 6f * scale, iconX, iconY);
                    g.DrawLine(ip, iconX, iconY, iconX + 5f * scale, iconY);
                } else if (i == 2) {
                    g.DrawEllipse(ip, iconX - 9f * scale, iconY - 9f * scale, 18f * scale, 18f * scale);
                    g.FillEllipse(ib, iconX - 4f * scale, iconY - 4f * scale, 8f * scale, 8f * scale);
                }
            }

            using (Font lblFont = new Font("Segoe UI", (isActive ? 11.5f : 11f) * scale, isActive ? FontStyle.Bold : FontStyle.Regular))
            using (SolidBrush tb = new SolidBrush(isActive ? OnPrimaryContainerLight : SecondaryLight)) {
                StringFormat sf = new StringFormat() { Alignment = StringAlignment.Center };
                g.DrawString(labels[i], lblFont, tb, railW / 2f, curY + 42f * scale, sf);
            }
        }
    }

    public static void DrawButton(Graphics g, RectangleF rect, string text, Color bgColor, Color textColor, float scale, bool bold = true) {
        using (SolidBrush b = new SolidBrush(bgColor)) {
            FillRoundedRect(g, b, rect, rect.Height / 2f);
        }
        using (Font f = new Font("Segoe UI", 14f * scale, bold ? FontStyle.Bold : FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(textColor)) {
            StringFormat sf = new StringFormat() { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString(text, f, tb, rect, sf);
        }
    }

    public static void DrawOutlinedButton(Graphics g, RectangleF rect, string text, Color borderColor, Color textColor, float scale, bool bold = true) {
        using (Pen p = new Pen(borderColor, 1.5f * scale)) {
            DrawRoundedRect(g, p, rect, rect.Height / 2f);
        }
        using (Font f = new Font("Segoe UI", 13.5f * scale, bold ? FontStyle.Bold : FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(textColor)) {
            StringFormat sf = new StringFormat() { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString(text, f, tb, rect, sf);
        }
    }

    public static void DrawBadge(Graphics g, RectangleF rect, string text, Color bgColor, Color textColor, float scale) {
        using (SolidBrush b = new SolidBrush(bgColor)) {
            FillRoundedRect(g, b, rect, rect.Height / 2f);
        }
        using (Font f = new Font("Segoe UI", 11f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(textColor)) {
            StringFormat sf = new StringFormat() { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString(text, f, tb, rect, sf);
        }
    }

    // Vector Icon Drawing Routines
    public static void DrawPhoneVector(Graphics g, float cx, float cy, float scale, Color color) {
        float w = 20f * scale;
        float h = 32f * scale;
        RectangleF r = new RectangleF(cx - w / 2f, cy - h / 2f, w, h);
        using (Pen p = new Pen(color, 2f * scale)) {
            DrawRoundedRect(g, p, r, 4f * scale);
            g.DrawLine(p, cx - 4f * scale, cy - h / 2f + 5f * scale, cx + 4f * scale, cy - h / 2f + 5f * scale);
            g.DrawEllipse(p, cx - 1.5f * scale, cy + h / 2f - 6f * scale, 3f * scale, 3f * scale);
        }
    }

    public static void DrawLaptopVector(Graphics g, float cx, float cy, float scale, Color color) {
        float sw = 28f * scale;
        float sh = 18f * scale;
        RectangleF sr = new RectangleF(cx - sw / 2f, cy - sh / 2f - 3f * scale, sw, sh);
        using (Pen p = new Pen(color, 2f * scale)) {
            DrawRoundedRect(g, p, sr, 3f * scale);
            g.DrawLine(p, cx - 20f * scale, cy + sh / 2f, cx + 20f * scale, cy + sh / 2f);
        }
    }

    public static void DrawTabletVector(Graphics g, float cx, float cy, float scale, Color color) {
        float w = 26f * scale;
        float h = 34f * scale;
        RectangleF r = new RectangleF(cx - w / 2f, cy - h / 2f, w, h);
        using (Pen p = new Pen(color, 2f * scale)) {
            DrawRoundedRect(g, p, r, 5f * scale);
            g.DrawEllipse(p, cx - 1.5f * scale, cy - h / 2f + 4f * scale, 3f * scale, 3f * scale);
        }
    }

    public static void DrawShieldVector(Graphics g, float cx, float cy, float size, Color fillColor, Color checkColor) {
        GraphicsPath path = new GraphicsPath();
        float half = size / 2f;
        path.AddLine(cx - half, cy - half * 0.8f, cx + half, cy - half * 0.8f);
        path.AddLine(cx + half, cy - half * 0.8f, cx + half * 0.9f, cy + half * 0.2f);
        path.AddCurve(new PointF[] {
            new PointF(cx + half * 0.9f, cy + half * 0.2f),
            new PointF(cx + half * 0.4f, cy + half * 0.8f),
            new PointF(cx, cy + half)
        });
        path.AddCurve(new PointF[] {
            new PointF(cx, cy + half),
            new PointF(cx - half * 0.4f, cy + half * 0.8f),
            new PointF(cx - half * 0.9f, cy + half * 0.2f)
        });
        path.CloseFigure();

        using (SolidBrush sb = new SolidBrush(fillColor)) {
            g.FillPath(sb, path);
        }
        using (Pen cp = new Pen(checkColor, size * 0.12f)) {
            cp.StartCap = LineCap.Round;
            cp.EndCap = LineCap.Round;
            g.DrawLine(cp, cx - half * 0.4f, cy, cx - half * 0.1f, cy + half * 0.35f);
            g.DrawLine(cp, cx - half * 0.1f, cy + half * 0.35f, cx + half * 0.45f, cy - half * 0.3f);
        }
        path.Dispose();
    }

    public static void DrawLockVector(Graphics g, float cx, float cy, float scale, Color color) {
        float bw = 18f * scale;
        float bh = 14f * scale;
        RectangleF br = new RectangleF(cx - bw / 2f, cy - 2f * scale, bw, bh);
        using (SolidBrush sb = new SolidBrush(color)) {
            FillRoundedRect(g, sb, br, 3f * scale);
        }
        using (Pen sp = new Pen(color, 2.2f * scale)) {
            g.DrawArc(sp, cx - 6f * scale, cy - 12f * scale, 12f * scale, 14f * scale, 180, 180);
        }
    }

    // --------------------------------------------------------------------------------------------------
    // SCREEN 1: Home / Device Discovery
    // --------------------------------------------------------------------------------------------------
    public static void RenderScreen1(Graphics g, float w, float h, float scale, Image logo) {
        DrawNavRail(g, h, scale, 0, logo);

        float leftX = 220f * scale;
        float curY = 56f * scale;

        // Header Title
        using (Font titleFont = new Font("Segoe UI", 26f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Glide \u2014 Fast Local Share", titleFont, tb, leftX, curY);
        }
        curY += 38f * scale;

        // Subtitle & Status Badge
        using (Font subFont = new Font("Segoe UI", 14.5f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(SecondaryLight)) {
            g.DrawString("Fast, private, offline peer-to-peer file sharing between local devices.", subFont, tb, leftX, curY);
        }
        DrawBadge(g, new RectangleF(w - 320f * scale, 56f * scale, 280f * scale, 34f * scale), "\u25CF Wi-Fi Direct & LAN Active", SuccessContainerLight, OnSuccessContainerLight, scale);
        curY += 46f * scale;

        // Quick Actions Row (Send / Receive)
        float btnW = 280f * scale;
        float btnH = 56f * scale;
        DrawButton(g, new RectangleF(leftX, curY, btnW, btnH), "\u27A4  Send files", PrimaryLight, OnPrimaryLight, scale);
        DrawButton(g, new RectangleF(leftX + btnW + 24f * scale, curY, btnW, btnH), "\u2913  Receive files", PrimaryContainerLight, OnPrimaryContainerLight, scale);
        curY += btnH + 32f * scale;

        // Privacy & Security Banner Card
        float contentW = w - leftX - 48f * scale;
        RectangleF bannerRect = new RectangleF(leftX, curY, contentW, 90f * scale);
        DrawCard(g, bannerRect, SurfaceLowLight, SurfaceHighLight, 16f * scale);

        DrawShieldVector(g, leftX + 48f * scale, curY + 45f * scale, 46f * scale, PrimaryLight, OnPrimaryLight);

        using (Font bf = new Font("Segoe UI", 15f * scale, FontStyle.Bold))
        using (SolidBrush btb = new SolidBrush(OnBgLight)) {
            g.DrawString("Private & Direct Local Transfer", bf, btb, leftX + 85f * scale, curY + 20f * scale);
        }
        using (Font df = new Font("Segoe UI", 12.5f * scale, FontStyle.Regular))
        using (SolidBrush dtb = new SolidBrush(SecondaryLight)) {
            g.DrawString("Zero cloud storage \u2022 Direct device-to-device Wi-Fi Direct & LAN \u2022 Cryptographic SHA-256 verification on every transfer.", df, dtb, leftX + 85f * scale, curY + 48f * scale);
        }
        curY += 114f * scale;

        // Nearby Discovered Peers Header
        using (Font phf = new Font("Segoe UI", 19f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Discovered nearby devices", phf, tb, leftX, curY);
        }
        using (Font psf = new Font("Segoe UI", 13.5f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(SecondaryLight)) {
            g.DrawString("3 peers discovered nearby \u2022 Tap Connect to stage files", psf, tb, leftX, curY + 28f * scale);
        }
        curY += 66f * scale;

        // 3 Discovered Peer Cards Row
        float cardGap = 20f * scale;
        float cardW = (contentW - (cardGap * 2)) / 3f;
        float cardH = 260f * scale;

        string[] peerNames = new string[] { "Pixel 10 Pro", "MacBook Pro M3", "Galaxy Tab S10+" };
        string[] peerTrans = new string[] { "Android 15 \u2022 Direct Wi-Fi Direct", "macOS Sonoma \u2022 Glide LAN (TLS 1.3)", "Android 15 \u2022 Nearby Connections" };
        string[] peerBadges = new string[] { "Ready to pair", "Connected", "Paired" };
        Color[] badgeBgs = new Color[] { PrimaryContainerLight, SuccessContainerLight, SecondaryContainerLight };
        Color[] badgeTxs = new Color[] { OnPrimaryContainerLight, OnSuccessContainerLight, OnSecondaryContainerLight };

        for (int p = 0; p < 3; p++) {
            RectangleF peerRect = new RectangleF(leftX + (p * (cardW + cardGap)), curY, cardW, cardH);
            DrawCard(g, peerRect, Color.White, SurfaceHighLight, 16f * scale);

            // Icon circle with vector icon
            float iconCircle = 52f * scale;
            float icX = peerRect.X + 24f * scale + iconCircle / 2f;
            float icY = peerRect.Y + 24f * scale + iconCircle / 2f;
            using (SolidBrush cb = new SolidBrush(badgeBgs[p])) {
                g.FillEllipse(cb, peerRect.X + 24f * scale, peerRect.Y + 24f * scale, iconCircle, iconCircle);
            }
            if (p == 0) DrawPhoneVector(g, icX, icY, scale, badgeTxs[p]);
            else if (p == 1) DrawLaptopVector(g, icX, icY, scale, badgeTxs[p]);
            else DrawTabletVector(g, icX, icY, scale, badgeTxs[p]);

            // Peer name & details
            using (Font pnf = new Font("Segoe UI", 17f * scale, FontStyle.Bold))
            using (SolidBrush pnb = new SolidBrush(OnBgLight)) {
                g.DrawString(peerNames[p], pnf, pnb, peerRect.X + 24f * scale, peerRect.Y + 92f * scale);
            }
            using (Font pdf = new Font("Segoe UI", 12f * scale, FontStyle.Regular))
            using (SolidBrush pdb = new SolidBrush(SecondaryLight)) {
                g.DrawString(peerTrans[p], pdf, pdb, peerRect.X + 24f * scale, peerRect.Y + 122f * scale);
            }

            DrawBadge(g, new RectangleF(peerRect.X + 24f * scale, peerRect.Y + 152f * scale, 120f * scale, 26f * scale), peerBadges[p], badgeBgs[p], badgeTxs[p], scale);
            DrawButton(g, new RectangleF(peerRect.X + 24f * scale, peerRect.Y + 194f * scale, cardW - 48f * scale, 44f * scale), "Connect & Send \u2192", PrimaryLight, OnPrimaryLight, scale);
        }
        curY += cardH + 32f * scale;

        // Recent Activity Card
        RectangleF actRect = new RectangleF(leftX, curY, contentW, 90f * scale);
        DrawCard(g, actRect, Color.White, SurfaceHighLight, 16f * scale);
        using (Font af = new Font("Segoe UI", 15f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Recent Transfer Activity", af, tb, leftX + 24f * scale, curY + 20f * scale);
        }
        using (Font asf = new Font("Segoe UI", 12.5f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(SecondaryLight)) {
            g.DrawString("Completed: 49.6 MB from Galaxy Tab S10+ \u2022 Today, 3:47 PM \u2022 2 files \u2022 SHA-256 Verified", asf, tb, leftX + 24f * scale, curY + 48f * scale);
        }
        DrawBadge(g, new RectangleF(actRect.Right - 150f * scale, curY + 28f * scale, 120f * scale, 30f * scale), "\u2713 Verified", SuccessContainerLight, OnSuccessContainerLight, scale);
    }

    // --------------------------------------------------------------------------------------------------
    // SCREEN 2: File & Batch Selection
    // --------------------------------------------------------------------------------------------------
    public static void RenderScreen2(Graphics g, float w, float h, float scale, Image logo) {
        DrawNavRail(g, h, scale, 0, logo);

        float leftX = 220f * scale;
        float curY = 56f * scale;
        float contentW = w - leftX - 48f * scale;

        DrawOutlinedButton(g, new RectangleF(leftX, curY, 110f * scale, 38f * scale), "\u2190 Back", OutlineLight, OnBgLight, scale);
        using (Font titleFont = new Font("Segoe UI", 26f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Select files to send", titleFont, tb, leftX + 130f * scale, curY);
        }
        curY += 46f * scale;

        using (Font subFont = new Font("Segoe UI", 14.5f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(SecondaryLight)) {
            g.DrawString("Private peer-to-peer transfer without broad storage access permissions.", subFont, tb, leftX, curY);
        }
        curY += 36f * scale;

        // Target Recipient Pill Card
        RectangleF recRect = new RectangleF(leftX, curY, contentW, 58f * scale);
        DrawCard(g, recRect, SurfaceLowLight, SurfaceHighLight, 14f * scale, false);
        using (Font rtf = new Font("Segoe UI", 14f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Target peer: Pixel 10 Pro (Wi-Fi Direct \u2022 Secure PIN Authenticated)", rtf, tb, leftX + 20f * scale, curY + 18f * scale);
        }
        DrawBadge(g, new RectangleF(recRect.Right - 160f * scale, curY + 14f * scale, 140f * scale, 28f * scale), "High-Speed Stream", PrimaryContainerLight, OnPrimaryContainerLight, scale);
        curY += 78f * scale;

        // Two Split Columns
        float leftColW = 540f * scale;
        float rightColW = contentW - leftColW - 24f * scale;
        float colsH = 680f * scale;

        // Left Card: Pickers
        RectangleF leftColRect = new RectangleF(leftX, curY, leftColW, colsH);
        DrawCard(g, leftColRect, Color.White, SurfaceHighLight, 16f * scale);

        float lY = curY + 28f * scale;
        using (Font cf = new Font("Segoe UI", 18f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Add files to transfer batch", cf, tb, leftX + 24f * scale, lY);
        }
        lY += 34f * scale;
        using (Font csf = new Font("Segoe UI", 13f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(SecondaryLight)) {
            g.DrawString("Select media, documents, or archives from your storage:", csf, tb, leftX + 24f * scale, lY);
        }
        lY += 46f * scale;

        float pBtnW = leftColW - 48f * scale;
        float pBtnH = 62f * scale;
        DrawButton(g, new RectangleF(leftX + 24f * scale, lY, pBtnW, pBtnH), "Choose photos & videos", PrimaryLight, OnPrimaryLight, scale);
        lY += pBtnH + 20f * scale;
        DrawButton(g, new RectangleF(leftX + 24f * scale, lY, pBtnW, pBtnH), "Choose files & documents", SecondaryContainerLight, OnSecondaryContainerLight, scale);
        lY += pBtnH + 20f * scale;
        DrawOutlinedButton(g, new RectangleF(leftX + 24f * scale, lY, pBtnW, pBtnH), "Select folder tree", OutlineLight, OnBgLight, scale);
        lY += pBtnH + 40f * scale;

        // Batch ceilings notice box
        RectangleF noticeRect = new RectangleF(leftX + 24f * scale, lY, pBtnW, 200f * scale);
        DrawCard(g, noticeRect, SurfaceLowLight, SurfaceHighLight, 12f * scale, false);
        using (Font ntf = new Font("Segoe UI", 14f * scale, FontStyle.Bold))
        using (SolidBrush ntb = new SolidBrush(OnBgLight)) {
            g.DrawString("Batch limits & privacy protection:", ntf, ntb, noticeRect.X + 16f * scale, noticeRect.Y + 16f * scale);
        }
        using (Font nbf = new Font("Segoe UI", 12f * scale, FontStyle.Regular))
        using (SolidBrush nbb = new SolidBrush(SecondaryLight)) {
            string txt = "- Up to 1,000 files per single transfer batch\n- Maximum 64 GB per individual file\n- End-to-end streaming SHA-256 verification\n- Staged in app-private quarantine sandbox\n- Zero cloud sync or intermediate server relay";
            g.DrawString(txt, nbf, nbb, new RectangleF(noticeRect.X + 16f * scale, noticeRect.Y + 46f * scale, pBtnW - 32f * scale, 140f * scale));
        }

        // Right Card: Staged batch files
        float rightX = leftX + leftColW + 24f * scale;
        RectangleF rightColRect = new RectangleF(rightX, curY, rightColW, colsH);
        DrawCard(g, rightColRect, Color.White, SurfaceHighLight, 16f * scale);

        float rY = curY + 28f * scale;
        using (Font rf = new Font("Segoe UI", 18f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Staged Batch (4 items \u2022 3.64 GB)", rf, tb, rightX + 24f * scale, rY);
        }
        using (Font cf = new Font("Segoe UI", 13.5f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(ErrorLight)) {
            g.DrawString("Clear all", cf, tb, rightColRect.Right - 100f * scale, rY + 4f * scale);
        }
        rY += 46f * scale;

        string[] files = new string[] {
            "vacation_video_4k.mp4",
            "project_presentation_deck.key",
            "brand_guidelines_2026_vector.pdf",
            "holiday_photos_archive.zip"
        };
        string[] fileMeta = new string[] {
            "3.20 GB \u2022 Video (MP4) \u2022 3840x2160 60fps",
            "38.1 MB \u2022 Apple Keynote Presentation",
            "11.4 MB \u2022 Adobe Acrobat Document (PDF)",
            "390.5 MB \u2022 ZIP Compressed Archive (142 photos)"
        };
        string[] badges = new string[] { "MP4", "KEY", "PDF", "ZIP" };
        Color[] fColors = new Color[] { Color.FromArgb(126, 34, 206), Color.FromArgb(29, 78, 216), Color.FromArgb(185, 28, 28), Color.FromArgb(180, 83, 9) };

        for (int fi = 0; fi < 4; fi++) {
            RectangleF itemRect = new RectangleF(rightX + 20f * scale, rY, rightColW - 40f * scale, 95f * scale);
            DrawCard(g, itemRect, SurfaceLowLight, SurfaceHighLight, 12f * scale, false);

            RectangleF bbRect = new RectangleF(itemRect.X + 16f * scale, itemRect.Y + 22f * scale, 52f * scale, 52f * scale);
            using (SolidBrush bb = new SolidBrush(fColors[fi])) {
                FillRoundedRect(g, bb, bbRect, 10f * scale);
            }
            using (Font bbf = new Font("Segoe UI", 12f * scale, FontStyle.Bold))
            using (SolidBrush bbt = new SolidBrush(Color.White)) {
                StringFormat sf = new StringFormat() { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
                g.DrawString(badges[fi], bbf, bbt, bbRect, sf);
            }

            using (Font fnf = new Font("Segoe UI", 15.5f * scale, FontStyle.Bold))
            using (SolidBrush fnb = new SolidBrush(OnBgLight)) {
                g.DrawString(files[fi], fnf, fnb, itemRect.X + 82f * scale, itemRect.Y + 22f * scale);
            }
            using (Font fmf = new Font("Segoe UI", 12f * scale, FontStyle.Regular))
            using (SolidBrush fmb = new SolidBrush(SecondaryLight)) {
                g.DrawString(fileMeta[fi], fmf, fmb, itemRect.X + 82f * scale, itemRect.Y + 52f * scale);
            }

            using (Font chf = new Font("Segoe UI", 18f * scale, FontStyle.Bold))
            using (SolidBrush chb = new SolidBrush(PrimaryLight)) {
                g.DrawString("\u2713", chf, chb, itemRect.Right - 40f * scale, itemRect.Y + 30f * scale);
            }

            rY += 105f * scale;
        }

        RectangleF bottomBar = new RectangleF(rightX + 20f * scale, rightColRect.Bottom - 95f * scale, rightColW - 40f * scale, 75f * scale);
        DrawButton(g, bottomBar, "Send 4 files (3.64 GB) to Pixel 10 Pro \u2192", PrimaryLight, OnPrimaryLight, scale);
    }

    // --------------------------------------------------------------------------------------------------
    // SCREEN 3: Active Transfer Progress
    // --------------------------------------------------------------------------------------------------
    public static void RenderScreen3(Graphics g, float w, float h, float scale, Image logo) {
        DrawNavRail(g, h, scale, 0, logo);

        float leftX = 220f * scale;
        float curY = 56f * scale;
        float contentW = w - leftX - 48f * scale;

        DrawOutlinedButton(g, new RectangleF(leftX, curY, 110f * scale, 38f * scale), "\u2190 Back", OutlineLight, OnBgLight, scale);
        using (Font titleFont = new Font("Segoe UI", 26f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Active transfer", titleFont, tb, leftX + 130f * scale, curY);
        }
        curY += 46f * scale;

        using (Font subFont = new Font("Segoe UI", 14.5f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(SecondaryLight)) {
            g.DrawString("High-speed peer-to-peer Wi-Fi Direct streaming session.", subFont, tb, leftX, curY);
        }
        DrawBadge(g, new RectangleF(w - 340f * scale, 56f * scale, 300f * scale, 34f * scale), "Session: p2p-direct-742b", PrimaryContainerLight, OnPrimaryContainerLight, scale);
        curY += 44f * scale;

        // Big Main Active Transfer Card
        RectangleF activeCard = new RectangleF(leftX, curY, contentW, 420f * scale);
        DrawCard(g, activeCard, Color.White, SurfaceHighLight, 18f * scale);

        float aY = curY + 28f * scale;
        using (Font rtf = new Font("Segoe UI", 15f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(PrimaryLight)) {
            g.DrawString("Sending to Pixel 10 Pro (Wi-Fi Direct) \u2022 Item 1 of 4", rtf, tb, leftX + 32f * scale, aY);
        }
        aY += 36f * scale;

        using (Font cff = new Font("Segoe UI", 24f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("vacation_video_4k.mp4", cff, tb, leftX + 32f * scale, aY);
        }
        aY += 44f * scale;

        // Big percentage & metrics
        using (Font pctFont = new Font("Segoe UI", 56f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(PrimaryLight)) {
            g.DrawString("68%", pctFont, tb, leftX + 32f * scale, aY);
        }

        float mX = leftX + 240f * scale;
        using (Font mf = new Font("Segoe UI", 17f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("2.48 GB of 3.64 GB transferred", mf, tb, mX, aY + 12f * scale);
        }
        using (Font sf = new Font("Segoe UI", 15f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(BrandCyanAccent)) {
            g.DrawString("Speed: 34.2 MB/s  \u2022  ~34s remaining", sf, tb, mX, aY + 42f * scale);
        }
        aY += 92f * scale;

        // Gradient Progress Bar
        float barW = contentW - 64f * scale;
        float barH = 16f * scale;
        RectangleF trackRect = new RectangleF(leftX + 32f * scale, aY, barW, barH);
        using (SolidBrush tb = new SolidBrush(PrimaryContainerLight)) {
            FillRoundedRect(g, tb, trackRect, barH / 2f);
        }
        RectangleF fillRect = new RectangleF(leftX + 32f * scale, aY, barW * 0.68f, barH);
        using (LinearGradientBrush lgb = new LinearGradientBrush(fillRect, PrimaryLight, BrandCyanAccent, 0f)) {
            FillRoundedRect(g, lgb, fillRect, barH / 2f);
        }
        aY += barH + 32f * scale;

        using (Font stf = new Font("Segoe UI", 12.5f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(SecondaryLight)) {
            g.DrawString("TLS 1.3 encrypted \u2022 Direct stream into private quarantine sandbox \u2022 Streaming SHA-256 integrity check", stf, tb, leftX + 32f * scale, aY + 12f * scale);
        }
        DrawOutlinedButton(g, new RectangleF(activeCard.Right - 220f * scale, aY, 180f * scale, 44f * scale), "Cancel transfer", ErrorLight, ErrorLight, scale);
        curY += 450f * scale;

        // Batch Queue Progress Card
        RectangleF queueCard = new RectangleF(leftX, curY, contentW, 420f * scale);
        DrawCard(g, queueCard, Color.White, SurfaceHighLight, 18f * scale);

        float qY = curY + 24f * scale;
        using (Font qhf = new Font("Segoe UI", 18f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Batch Transfer Queue (4 items)", qhf, tb, leftX + 32f * scale, qY);
        }
        qY += 42f * scale;

        string[] qNames = new string[] { "1. vacation_video_4k.mp4 (3.20 GB)", "2. project_presentation_deck.key (38.1 MB)", "3. brand_guidelines_2026_vector.pdf (11.4 MB)", "4. holiday_photos_archive.zip (390.5 MB)" };
        string[] qStatus = new string[] { "Transferring \u2022 68% (34.2 MB/s)", "Queued", "Queued", "Queued" };
        Color[] qBgs = new Color[] { PrimaryContainerLight, SurfaceLowLight, SurfaceLowLight, SurfaceLowLight };
        Color[] qTxs = new Color[] { OnPrimaryContainerLight, SecondaryLight, SecondaryLight, SecondaryLight };

        for (int qi = 0; qi < 4; qi++) {
            RectangleF qr = new RectangleF(leftX + 32f * scale, qY, contentW - 64f * scale, 64f * scale);
            DrawCard(g, qr, SurfaceLowLight, SurfaceHighLight, 10f * scale, false);
            using (Font qnf = new Font("Segoe UI", 14.5f * scale, qi == 0 ? FontStyle.Bold : FontStyle.Regular))
            using (SolidBrush tb = new SolidBrush(OnBgLight)) {
                g.DrawString(qNames[qi], qnf, tb, qr.X + 20f * scale, qr.Y + 20f * scale);
            }
            DrawBadge(g, new RectangleF(qr.Right - 280f * scale, qr.Y + 16f * scale, 260f * scale, 32f * scale), qStatus[qi], qBgs[qi], qTxs[qi], scale);
            qY += 76f * scale;
        }
    }

    // --------------------------------------------------------------------------------------------------
    // SCREEN 4: Receive Consent & Verification
    // --------------------------------------------------------------------------------------------------
    public static void RenderScreen4(Graphics g, float w, float h, float scale, Image logo) {
        DrawNavRail(g, h, scale, 0, logo);

        float leftX = 220f * scale;
        float curY = 56f * scale;
        float contentW = w - leftX - 48f * scale;

        using (Font titleFont = new Font("Segoe UI", 26f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Incoming transfer request", titleFont, tb, leftX, curY);
        }
        curY += 40f * scale;
        using (Font subFont = new Font("Segoe UI", 14.5f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(SecondaryLight)) {
            g.DrawString("A nearby device wants to share files with you over private Wi-Fi Direct.", subFont, tb, leftX, curY);
        }
        curY += 50f * scale;

        // Centered Verification Card
        float cardW = Math.Min(contentW, 1160f * scale);
        float cardX = leftX + (contentW - cardW) / 2f;
        RectangleF consentCard = new RectangleF(cardX, curY, cardW, 820f * scale);
        DrawCard(g, consentCard, Color.White, SurfaceHighLight, 20f * scale);

        float cY = curY + 36f * scale;

        // Shield Vector
        DrawShieldVector(g, cardX + cardW / 2f, cY + 30f * scale, 56f * scale, PrimaryLight, OnPrimaryLight);
        cY += 70f * scale;

        using (Font chf = new Font("Segoe UI", 24f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            StringFormat cAlign = new StringFormat() { Alignment = StringAlignment.Center };
            g.DrawString("Galaxy Tab S10+", chf, tb, cardX + cardW / 2f, cY, cAlign);
        }
        cY += 38f * scale;
        using (Font csf = new Font("Segoe UI", 14.5f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(SecondaryLight)) {
            StringFormat cAlign = new StringFormat() { Alignment = StringAlignment.Center };
            g.DrawString("Android 15 \u2022 Local Wi-Fi Direct \u2022 Offered payload: 2 files (49.6 MB)", csf, tb, cardX + cardW / 2f, cY, cAlign);
        }
        cY += 40f * scale;

        // SAS Instruction
        using (Font inf = new Font("Segoe UI", 14f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            StringFormat cAlign = new StringFormat() { Alignment = StringAlignment.Center };
            g.DrawString("Compare the 6-digit mutual authentication code below with sender:", inf, tb, cardX + cardW / 2f, cY, cAlign);
        }
        cY += 34f * scale;

        // 6 Large SAS Digit Boxes
        int[] digits = new int[] { 4, 8, 2, 9, 1, 6 };
        float boxW = 84f * scale;
        float boxH = 96f * scale;
        float boxGap = 16f * scale;
        float totalBoxesW = (boxW * 6) + (boxGap * 5) + 32f * scale;
        float startBoxX = cardX + (cardW - totalBoxesW) / 2f;

        for (int d = 0; d < 6; d++) {
            float bX = startBoxX + (d * (boxW + boxGap)) + (d >= 3 ? 32f * scale : 0f);
            RectangleF bRect = new RectangleF(bX, cY, boxW, boxH);
            using (SolidBrush bgb = new SolidBrush(SurfaceLowLight)) {
                FillRoundedRect(g, bgb, bRect, 14f * scale);
            }
            using (Pen bp = new Pen(PrimaryLight, 2.5f * scale)) {
                DrawRoundedRect(g, bp, bRect, 14f * scale);
            }
            using (Font df = new Font("Segoe UI", 42f * scale, FontStyle.Bold))
            using (SolidBrush dtb = new SolidBrush(OnPrimaryContainerLight)) {
                StringFormat sf = new StringFormat() { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
                g.DrawString(digits[d].ToString(), df, dtb, bRect, sf);
            }
        }
        cY += boxH + 24f * scale;

        DrawBadge(g, new RectangleF(cardX + (cardW - 200f * scale) / 2f, cY, 200f * scale, 32f * scale), "Expires in 54s", WarningContainerLight, OnWarningContainerLight, scale);
        cY += 50f * scale;

        // Offered items box
        float offW = cardW - 80f * scale;
        RectangleF offRect = new RectangleF(cardX + 40f * scale, cY, offW, 140f * scale);
        DrawCard(g, offRect, SurfaceLowLight, SurfaceHighLight, 12f * scale, false);
        using (Font ohf = new Font("Segoe UI", 14f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Offered batch items:", ohf, tb, offRect.X + 20f * scale, offRect.Y + 16f * scale);
        }
        using (Font ofbf = new Font("Segoe UI", 13f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(SecondaryLight)) {
            g.DrawString("- project_milestone_deck_final.key (38.1 MB \u2022 Presentation)", ofbf, tb, offRect.X + 20f * scale, offRect.Y + 46f * scale);
            g.DrawString("- brand_guidelines_2026_vector.pdf (11.4 MB \u2022 PDF Document)", ofbf, tb, offRect.X + 20f * scale, offRect.Y + 74f * scale);
            g.DrawString("\u2713 Device storage: 41.91 GB available (49.6 MB needed)", ofbf, tb, offRect.X + 20f * scale, offRect.Y + 104f * scale);
        }
        cY += 164f * scale;

        // Decision buttons
        float actBtnW = (cardW - 100f * scale) / 2f;
        float actBtnH = 58f * scale;
        DrawButton(g, new RectangleF(cardX + 40f * scale, cY, actBtnW, actBtnH), "\u2713 Numbers match (Accept)", PrimaryLight, OnPrimaryLight, scale);
        DrawOutlinedButton(g, new RectangleF(cardX + 60f * scale + actBtnW, cY, actBtnW, actBtnH), "\u2715 Decline", ErrorLight, ErrorLight, scale);
    }

    // --------------------------------------------------------------------------------------------------
    // SCREEN 5: Transfer History (Two-Pane Layout)
    // --------------------------------------------------------------------------------------------------
    public static void RenderScreen5(Graphics g, float w, float h, float scale, Image logo) {
        DrawNavRail(g, h, scale, 1, logo);

        float leftX = 220f * scale;
        float curY = 56f * scale;
        float contentW = w - leftX - 48f * scale;

        using (Font titleFont = new Font("Segoe UI", 26f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Transfer history", titleFont, tb, leftX, curY);
        }

        // Filter chips on right
        float chipX = w - 460f * scale;
        DrawBadge(g, new RectangleF(chipX, curY, 90f * scale, 36f * scale), "All (12)", PrimaryContainerLight, OnPrimaryContainerLight, scale);
        DrawBadge(g, new RectangleF(chipX + 100f * scale, curY, 90f * scale, 36f * scale), "Sent (5)", Color.White, SecondaryLight, scale);
        DrawBadge(g, new RectangleF(chipX + 200f * scale, curY, 110f * scale, 36f * scale), "Received (7)", Color.White, SecondaryLight, scale);
        DrawOutlinedButton(g, new RectangleF(chipX + 320f * scale, curY, 90f * scale, 36f * scale), "Clear", OutlineLight, OnBgLight, scale);
        curY += 66f * scale;

        // Two-Pane Layout
        float leftPaneW = 560f * scale;
        float rightPaneW = contentW - leftPaneW - 24f * scale;
        float paneH = 920f * scale;

        // Left Pane
        RectangleF leftPaneRect = new RectangleF(leftX, curY, leftPaneW, paneH);
        DrawCard(g, leftPaneRect, Color.White, SurfaceHighLight, 18f * scale);

        float lpY = curY + 24f * scale;
        using (Font lpf = new Font("Segoe UI", 17f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Past transfer sessions", lpf, tb, leftX + 24f * scale, lpY);
        }
        lpY += 42f * scale;

        string[] histPeers = new string[] { "Galaxy Tab S10+", "Pixel 10 Pro", "MacBook Pro M3", "Linux Desktop (LAN)", "Pixel 8a" };
        string[] histMeta = new string[] { "Today, 3:47 PM \u2022 2 files \u2022 49.6 MB", "Today, 2:35 PM \u2022 4 files \u2022 3.64 GB", "Yesterday, 11:20 AM \u2022 1 file \u2022 14.5 MB", "Sep 12, 2026 \u2022 5 files \u2022 520.0 MB", "Sep 10, 2026 \u2022 1 file \u2022 8.2 MB" };
        string[] histDirs = new string[] { "Received", "Sent", "Sent", "Received", "Sent" };
        Color[] dirBgs = new Color[] { SuccessContainerLight, PrimaryContainerLight, PrimaryContainerLight, SuccessContainerLight, PrimaryContainerLight };
        Color[] dirTxs = new Color[] { OnSuccessContainerLight, OnPrimaryContainerLight, OnPrimaryContainerLight, OnSuccessContainerLight, OnPrimaryContainerLight };

        for (int hi = 0; hi < 5; hi++) {
            bool isSelected = (hi == 0);
            RectangleF sRect = new RectangleF(leftX + 16f * scale, lpY, leftPaneW - 32f * scale, 88f * scale);
            DrawCard(g, sRect, isSelected ? SurfaceLowLight : Color.White, isSelected ? PrimaryLight : SurfaceHighLight, 12f * scale, false);

            if (isSelected) {
                using (SolidBrush ab = new SolidBrush(PrimaryLight)) {
                    g.FillRectangle(ab, sRect.X, sRect.Y + 12f * scale, 5f * scale, sRect.Height - 24f * scale);
                }
            }

            DrawBadge(g, new RectangleF(sRect.X + 16f * scale, sRect.Y + 28f * scale, 88f * scale, 30f * scale), histDirs[hi], dirBgs[hi], dirTxs[hi], scale);

            using (Font hpf = new Font("Segoe UI", 15.5f * scale, isSelected ? FontStyle.Bold : FontStyle.Regular))
            using (SolidBrush hpb = new SolidBrush(OnBgLight)) {
                g.DrawString(histPeers[hi], hpf, hpb, sRect.X + 116f * scale, sRect.Y + 20f * scale);
            }
            using (Font hdf = new Font("Segoe UI", 12f * scale, FontStyle.Regular))
            using (SolidBrush hdb = new SolidBrush(SecondaryLight)) {
                g.DrawString(histMeta[hi], hdf, hdb, sRect.X + 116f * scale, sRect.Y + 50f * scale);
            }

            DrawBadge(g, new RectangleF(sRect.Right - 110f * scale, sRect.Y + 28f * scale, 95f * scale, 28f * scale), "Completed", SuccessContainerLight, OnSuccessContainerLight, scale);
            lpY += 100f * scale;
        }

        // Right Pane
        float rightX = leftX + leftPaneW + 24f * scale;
        RectangleF rightPaneRect = new RectangleF(rightX, curY, rightPaneW, paneH);
        DrawCard(g, rightPaneRect, Color.White, SurfaceHighLight, 18f * scale);

        float rpY = curY + 28f * scale;
        using (Font rhf = new Font("Segoe UI", 22f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Galaxy Tab S10+", rhf, tb, rightX + 32f * scale, rpY);
        }
        DrawBadge(g, new RectangleF(rightPaneRect.Right - 220f * scale, rpY, 180f * scale, 34f * scale), "Verified SHA-256", SuccessContainerLight, OnSuccessContainerLight, scale);
        rpY += 40f * scale;

        using (Font rmf = new Font("Segoe UI", 13.5f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(SecondaryLight)) {
            g.DrawString("Received transfer \u2022 September 14, 2026, 3:47 PM \u2022 Duration: 10s \u2022 Total payload: 49.6 MB", rmf, tb, rightX + 32f * scale, rpY);
        }
        rpY += 50f * scale;

        // File Details List
        string[] dFiles = new string[] { "project_milestone_deck_final.key", "brand_guidelines_2026_vector.pdf" };
        string[] dInfo = new string[] { "38.1 MB \u2022 application/octet-stream \u2022 SHA-256: 8a4f...9c12", "11.4 MB \u2022 application/pdf \u2022 SHA-256: d7e3...4a08" };
        string[] dBadges = new string[] { "KEY", "PDF" };
        Color[] dBgColors = new Color[] { Color.FromArgb(29, 78, 216), Color.FromArgb(185, 28, 28) };

        for (int di = 0; di < 2; di++) {
            RectangleF dr = new RectangleF(rightX + 32f * scale, rpY, rightPaneW - 64f * scale, 110f * scale);
            DrawCard(g, dr, SurfaceLowLight, SurfaceHighLight, 12f * scale, false);

            RectangleF dbRect = new RectangleF(dr.X + 16f * scale, dr.Y + 28f * scale, 54f * scale, 54f * scale);
            using (SolidBrush dbb = new SolidBrush(dBgColors[di])) {
                FillRoundedRect(g, dbb, dbRect, 10f * scale);
            }
            using (Font dbf = new Font("Segoe UI", 13f * scale, FontStyle.Bold))
            using (SolidBrush dbt = new SolidBrush(Color.White)) {
                StringFormat sf = new StringFormat() { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
                g.DrawString(dBadges[di], dbf, dbt, dbRect, sf);
            }

            using (Font dnf = new Font("Segoe UI", 16f * scale, FontStyle.Bold))
            using (SolidBrush dnb = new SolidBrush(OnBgLight)) {
                g.DrawString(dFiles[di], dnf, dnb, dr.X + 86f * scale, dr.Y + 28f * scale);
            }
            using (Font dif = new Font("Segoe UI", 12f * scale, FontStyle.Regular))
            using (SolidBrush dib = new SolidBrush(SecondaryLight)) {
                g.DrawString(dInfo[di], dif, dib, dr.X + 86f * scale, dr.Y + 60f * scale);
            }

            DrawOutlinedButton(g, new RectangleF(dr.Right - 220f * scale, dr.Y + 34f * scale, 95f * scale, 42f * scale), "Open", OutlineLight, OnBgLight, scale);
            DrawButton(g, new RectangleF(dr.Right - 115f * scale, dr.Y + 34f * scale, 95f * scale, 42f * scale), "Export", PrimaryLight, OnPrimaryLight, scale);

            rpY += 130f * scale;
        }

        rpY += 20f * scale;
        RectangleF qnRect = new RectangleF(rightX + 32f * scale, rpY, rightPaneW - 64f * scale, 80f * scale);
        DrawCard(g, qnRect, SurfaceLowLight, SurfaceHighLight, 10f * scale, false);
        using (Font qnf = new Font("Segoe UI", 13f * scale, FontStyle.Regular))
        using (SolidBrush qnb = new SolidBrush(SecondaryLight)) {
            g.DrawString("Stored in app-private quarantine sandbox (doc/quarantine). Corrupted or partial items are never committed to user storage.", qnf, qnb, qnRect.X + 16f * scale, qnRect.Y + 24f * scale);
        }

        DrawButton(g, new RectangleF(rightX + 32f * scale, rightPaneRect.Bottom - 90f * scale, rightPaneW - 64f * scale, 56f * scale), "Export all files to public Downloads folder", PrimaryLight, OnPrimaryLight, scale);
    }

    // --------------------------------------------------------------------------------------------------
    // SCREEN 6: Settings & Privacy
    // --------------------------------------------------------------------------------------------------
    public static void RenderScreen6(Graphics g, float w, float h, float scale, Image logo) {
        DrawNavRail(g, h, scale, 2, logo);

        float leftX = 220f * scale;
        float curY = 56f * scale;
        float contentW = w - leftX - 48f * scale;

        using (Font titleFont = new Font("Segoe UI", 26f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Settings & Privacy", titleFont, tb, leftX, curY);
        }
        curY += 40f * scale;
        using (Font subFont = new Font("Segoe UI", 14.5f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(SecondaryLight)) {
            g.DrawString("Device visibility, local storage management, and zero-cloud privacy controls.", subFont, tb, leftX, curY);
        }
        curY += 50f * scale;

        float colW = (contentW - 24f * scale) / 2f;

        // Column 1
        float c1X = leftX;
        float c1Y = curY;

        // Card 1: Device Identity
        RectangleF dCard = new RectangleF(c1X, c1Y, colW, 240f * scale);
        DrawCard(g, dCard, Color.White, SurfaceHighLight, 16f * scale);
        using (Font cth = new Font("Segoe UI", 17f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Device identity & discovery", cth, tb, dCard.X + 24f * scale, dCard.Y + 22f * scale);
        }
        using (Font cf = new Font("Segoe UI", 14.5f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Device name: Glide Tablet", cf, tb, dCard.X + 24f * scale, dCard.Y + 68f * scale);
        }
        DrawOutlinedButton(g, new RectangleF(dCard.Right - 120f * scale, dCard.Y + 60f * scale, 95f * scale, 36f * scale), "Edit", OutlineLight, OnBgLight, scale);
        using (Font csf = new Font("Segoe UI", 12f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(SecondaryLight)) {
            g.DrawString("Visible to nearby peer devices over Wi-Fi Direct and LAN", csf, tb, dCard.X + 24f * scale, dCard.Y + 104f * scale);
            g.DrawString("Mutual SPAKE2 pairing fingerprint: glide-tab-8f3a", csf, tb, dCard.X + 24f * scale, dCard.Y + 130f * scale);
        }
        DrawBadge(g, new RectangleF(dCard.X + 24f * scale, dCard.Y + 172f * scale, 220f * scale, 30f * scale), "Visible to nearby peers: ON", SuccessContainerLight, OnSuccessContainerLight, scale);

        c1Y += 260f * scale;

        // Card 2: Appearance & Theme
        RectangleF tCard = new RectangleF(c1X, c1Y, colW, 260f * scale);
        DrawCard(g, tCard, Color.White, SurfaceHighLight, 16f * scale);
        using (Font cth = new Font("Segoe UI", 17f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Appearance & display", cth, tb, tCard.X + 24f * scale, tCard.Y + 22f * scale);
        }
        using (Font cf = new Font("Segoe UI", 14f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Theme mode:  \u25CF System default   \u25CB Light   \u25CB Dark", cf, tb, tCard.X + 24f * scale, tCard.Y + 68f * scale);
            g.DrawString("Dynamic colors (Material You):  ON", cf, tb, tCard.X + 24f * scale, tCard.Y + 114f * scale);
            g.DrawString("Reduced motion (Disable spring animations):  OFF", cf, tb, tCard.X + 24f * scale, tCard.Y + 158f * scale);
        }
        using (Font csf = new Font("Segoe UI", 12f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(SecondaryLight)) {
            g.DrawString("Glide automatically adapts to your system theme and tablet posture.", csf, tb, tCard.X + 24f * scale, tCard.Y + 204f * scale);
        }

        c1Y += 280f * scale;

        // Card 3: Network Transports
        RectangleF netCard = new RectangleF(c1X, c1Y, colW, 240f * scale);
        DrawCard(g, netCard, Color.White, SurfaceHighLight, 16f * scale);
        using (Font cth = new Font("Segoe UI", 17f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Enabled network transports", cth, tb, netCard.X + 24f * scale, netCard.Y + 22f * scale);
        }
        using (Font cf = new Font("Segoe UI", 13.5f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("- Wi-Fi Direct (High-throughput P2P radio): Enabled", cf, tb, netCard.X + 24f * scale, netCard.Y + 66f * scale);
            g.DrawString("- TLS 1.3 Local Socket (LAN discovery): Enabled", cf, tb, netCard.X + 24f * scale, netCard.Y + 104f * scale);
            g.DrawString("- Bluetooth Low Energy Handshake: Enabled", cf, tb, netCard.X + 24f * scale, netCard.Y + 142f * scale);
        }
        DrawBadge(g, new RectangleF(netCard.X + 24f * scale, netCard.Y + 180f * scale, 240f * scale, 30f * scale), "All transports active & secure", PrimaryContainerLight, OnPrimaryContainerLight, scale);

        // Column 2
        float c2X = leftX + colW + 24f * scale;
        float c2Y = curY;

        // Card 4: Storage Sandbox
        RectangleF sCard = new RectangleF(c2X, c2Y, colW, 280f * scale);
        DrawCard(g, sCard, Color.White, SurfaceHighLight, 16f * scale);
        using (Font cth = new Font("Segoe UI", 17f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Storage & Quarantine Sandbox", cth, tb, sCard.X + 24f * scale, sCard.Y + 22f * scale);
        }
        using (Font cf = new Font("Segoe UI", 14f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("App-private quarantine used:  3.64 GB", cf, tb, sCard.X + 24f * scale, sCard.Y + 68f * scale);
            g.DrawString("Device storage available:  41.91 GB", cf, tb, sCard.X + 24f * scale, sCard.Y + 104f * scale);
            g.DrawString("Storage location:  App-private sandbox directory", cf, tb, sCard.X + 24f * scale, sCard.Y + 140f * scale);
        }
        using (Font csf = new Font("Segoe UI", 12f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(SecondaryLight)) {
            g.DrawString("Received files are isolated until you choose to export them.", csf, tb, sCard.X + 24f * scale, sCard.Y + 176f * scale);
        }
        DrawOutlinedButton(g, new RectangleF(sCard.X + 24f * scale, sCard.Y + 210f * scale, 260f * scale, 42f * scale), "Clear quarantine cache", ErrorLight, ErrorLight, scale);

        c2Y += 300f * scale;

        // Card 5: Privacy & Zero Cloud Guarantee
        RectangleF pCard = new RectangleF(c2X, c2Y, colW, 280f * scale);
        DrawCard(g, pCard, Color.White, SurfaceHighLight, 16f * scale);
        using (Font cth = new Font("Segoe UI", 17f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Privacy & Zero-Cloud Guarantee", cth, tb, pCard.X + 24f * scale, pCard.Y + 22f * scale);
        }
        using (Font cf = new Font("Segoe UI", 13.5f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("- 100% Offline & Peer-to-Peer direct transmission", cf, tb, pCard.X + 24f * scale, pCard.Y + 66f * scale);
            g.DrawString("- Zero intermediate servers, cloud relays, or web bridges", cf, tb, pCard.X + 24f * scale, pCard.Y + 102f * scale);
            g.DrawString("- No accounts, registration, email, or passwords required", cf, tb, pCard.X + 24f * scale, pCard.Y + 138f * scale);
            g.DrawString("- Zero tracking SDKs, analytics, or advertising telemetry", cf, tb, pCard.X + 24f * scale, pCard.Y + 174f * scale);
        }
        DrawBadge(g, new RectangleF(pCard.X + 24f * scale, pCard.Y + 216f * scale, 240f * scale, 32f * scale), "Zero-Cloud Verified", SuccessContainerLight, OnSuccessContainerLight, scale);

        c2Y += 300f * scale;

        // Card 6: About & Licenses
        RectangleF aCard = new RectangleF(c2X, c2Y, colW, 200f * scale);
        DrawCard(g, aCard, Color.White, SurfaceHighLight, 16f * scale);
        using (Font cth = new Font("Segoe UI", 17f * scale, FontStyle.Bold))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("About Glide", cth, tb, aCard.X + 24f * scale, aCard.Y + 22f * scale);
        }
        using (Font cf = new Font("Segoe UI", 14f * scale, FontStyle.Regular))
        using (SolidBrush tb = new SolidBrush(OnBgLight)) {
            g.DrawString("Glide Version 1.0.0 (Build 1, targetSdk 37)", cf, tb, aCard.X + 24f * scale, aCard.Y + 66f * scale);
            g.DrawString("Production Release Candidate \u2022 16 KB Page Aligned", cf, tb, aCard.X + 24f * scale, aCard.Y + 98f * scale);
        }
        DrawOutlinedButton(g, new RectangleF(aCard.X + 24f * scale, aCard.Y + 134f * scale, 180f * scale, 38f * scale), "Open source licenses", OutlineLight, OnBgLight, scale);
        DrawOutlinedButton(g, new RectangleF(aCard.X + 220f * scale, aCard.Y + 134f * scale, 150f * scale, 38f * scale), "Privacy policy", OutlineLight, OnBgLight, scale);
    }

    // --------------------------------------------------------------------------------------------------
    // MASTER DISPATCHER
    // --------------------------------------------------------------------------------------------------
    public static void GenerateScreen(int screenNumber, string outPath, int w, int h, Image logo) {
        float scale = w / 1920f;
        using (Bitmap bmp = new Bitmap(w, h, PixelFormat.Format24bppRgb)) {
            using (Graphics g = Graphics.FromImage(bmp)) {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
                g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                g.Clear(BgLight);

                DrawStatusBar(g, w, scale);
                DrawGestureBar(g, w, h, scale);

                switch (screenNumber) {
                    case 1: RenderScreen1(g, w, h, scale, logo); break;
                    case 2: RenderScreen2(g, w, h, scale, logo); break;
                    case 3: RenderScreen3(g, w, h, scale, logo); break;
                    case 4: RenderScreen4(g, w, h, scale, logo); break;
                    case 5: RenderScreen5(g, w, h, scale, logo); break;
                    case 6: RenderScreen6(g, w, h, scale, logo); break;
                    default: throw new ArgumentException("Invalid screen number: " + screenNumber);
                }
            }
            bmp.Save(outPath, ImageFormat.Png);
        }
    }
}
'@

$tempRunner = "$PSScriptRoot\_temp_tablet_runner.ps1"
$execScript = @"
`$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
$csharpCode
'@ -ReferencedAssemblies System.Drawing

`$logoPath = '$projectRoot\assets\masters\glide-symbol-gradient.png'
`$logo = if (Test-Path `$logoPath) { [System.Drawing.Image]::FromFile(`$logoPath) } else { `$null }

`$targets = @(
    @{ Dir = '$OutputDir7Inch'; W = 1920; H = 1200; Name = '7-inch' },
    @{ Dir = '$OutputDir10Inch'; W = 2560; H = 1600; Name = '10-inch' },
    @{ Dir = '$OutputDirGeneralTablet'; W = 1920; H = 1200; Name = 'general tablet' }
)

`$fileNames = @(
    '01_home_discovery.png',
    '02_file_selection.png',
    '03_active_transfer.png',
    '04_receive_consent.png',
    '05_transfer_history.png',
    '06_settings_privacy.png'
)

foreach (`$t in `$targets) {
    Write-Host "`nGenerating `$(`$t.Name) screenshots (`$(`$t.W)x`$(`$t.H)) into `$(`$t.Dir)..." -ForegroundColor Yellow
    for (`$i = 1; `$i -le 6; `$i++) {
        `$fn = `$fileNames[`$i - 1]
        `$out = Join-Path `$t.Dir `$fn
        [TabletScreenshotComposer]::GenerateScreen(`$i, `$out, `$t.W, `$t.H, `$logo)
        Write-Host "  -> Generated `$fn" -ForegroundColor Green
    }
}

if (`$logo -ne `$null) { `$logo.Dispose() }
Write-Host "`nAll tablet screenshots generated successfully!" -ForegroundColor Cyan
"@

try {
    [System.IO.File]::WriteAllText($tempRunner, $execScript, [System.Text.Encoding]::UTF8)

    $pinfo = New-Object System.Diagnostics.ProcessStartInfo
    $pinfo.FileName = "powershell.exe"
    $pinfo.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$tempRunner`""
    $pinfo.UseShellExecute = $false
    $pinfo.RedirectStandardOutput = $true
    $pinfo.RedirectStandardError = $true

    $proc = [System.Diagnostics.Process]::Start($pinfo)
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()

    Write-Host $stdout
    if ($proc.ExitCode -ne 0) {
        Write-Host $stderr -ForegroundColor Red
        throw "Tablet screenshot generation failed with exit code $($proc.ExitCode)"
    }
}
finally {
    Remove-Item -Path $tempRunner -Force -ErrorAction SilentlyContinue
}
