import Foundation

/// Cross-platform design tokens and color definitions for Glide user interfaces.
/// Conforms to brand identity established across Android, Windows, and Web.
public enum GlideTheme {
    // Brand Colors
    public static let brandBlueHex = "#0D6EFD"
    public static let brandSuccessHex = "#198754"
    public static let brandWarningHex = "#FFC107"
    public static let brandDangerHex = "#DC3545"

    // Surface Palettes (Dark Theme)
    public static let darkBackgroundHex = "#121212"
    public static let darkSurfaceHex = "#1E1E1E"
    public static let darkTextPrimaryHex = "#FFFFFF"
    public static let darkTextSecondaryHex = "#A0A0A0"

    // Surface Palettes (Light Theme)
    public static let lightBackgroundHex = "#F8F9FA"
    public static let lightSurfaceHex = "#FFFFFF"
    public static let lightTextPrimaryHex = "#212529"
    public static let lightTextSecondaryHex = "#6C757D"

    // Component Radiuses
    public static let cardCornerRadius: Double = 16.0
    public static let buttonCornerRadius: Double = 12.0
    public static let tagCornerRadius: Double = 8.0

    // Spacing
    public static let spacingXs: Double = 4.0
    public static let spacingSm: Double = 8.0
    public static let spacingMd: Double = 16.0
    public static let spacingLg: Double = 24.0
    public static let spacingXl: Double = 32.0

    /// Converts a hex color string (e.g. "#0D6EFD" or "0D6EFD") into RGB color components (0.0 .. 1.0).
    public static func hexToRgb(_ hexString: String) -> (red: Double, green: Double, blue: Double)? {
        var hex = hexString.trimmingCharacters(in: .whitespacesAndNewlines)
        if hex.hasPrefix("#") {
            hex.removeFirst()
        }

        guard hex.count == 6, let intVal = UInt32(hex, radix: 16) else {
            return nil
        }

        let r = Double((intVal >> 16) & 0xFF) / 255.0
        let g = Double((intVal >> 8) & 0xFF) / 255.0
        let b = Double(intVal & 0xFF) / 255.0
        return (red: r, green: g, blue: b)
    }
}
