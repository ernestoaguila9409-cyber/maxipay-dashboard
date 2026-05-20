/** Landi C20 Pro built-in 58mm printer (~384 dots): Normal/Large ≈ 32 cols, X-Large ≈ 24. */
export const LANDI_CHARS_PER_LINE = [32, 32, 24] as const;

/** Landi receipt logo max width in printer dots (matches Android EscPosPrinter). */
export const LANDI_LOGO_WIDTH_PX = [192, 384] as const;
export const LOGO_SIZE_LABELS = ["Standard", "Large"] as const;

export function landiLogoMaxWidthPx(logoSize: number): number {
  const i = Math.max(0, Math.min(1, Math.floor(logoSize)));
  return LANDI_LOGO_WIDTH_PX[i];
}

/** Scaled preview width for dashboard receipt mock (not 1:1 with printer dots). */
export function landiLogoPreviewMaxWidthPx(logoSize: number): number {
  const i = Math.max(0, Math.min(1, Math.floor(logoSize)));
  return i === 1 ? 336 : 168;
}

/** Dejavoo P8 built-in printer: fixed 24 characters per line, no font size control. */
export const P8_CHARS_PER_LINE = 24;

export const FONT_SIZE_LABELS = ["Normal", "Large", "X-Large"] as const;

/**
 * Characters per line on the Landi built-in receipt printer (maxipaypos.com preview + limits).
 */
export function landiCharsPerLine(fontSizeSetting: number): number {
  const i = Math.max(0, Math.min(2, Math.floor(fontSizeSetting)));
  return LANDI_CHARS_PER_LINE[i];
}

/** @deprecated alias — dashboard preview and forms use Landi widths */
export function thermalCharsPerLine(fontSizeSetting: number): number {
  return landiCharsPerLine(fontSizeSetting);
}

export function clampSingleLine(value: string, maxChars: number): string {
  const cap = Math.max(1, maxChars);
  const first = value.replace(/\r\n/g, "\n").split("\n")[0] ?? "";
  return first.slice(0, cap);
}

export function clampMultiline(
  value: string,
  maxCharsPerLine: number,
  maxLines: number
): string {
  const cap = Math.max(1, maxCharsPerLine);
  const lines = value.replace(/\r\n/g, "\n").split("\n").slice(0, maxLines);
  return lines.map((l) => l.slice(0, cap)).join("\n");
}

/**
 * Wraps plain text to fit a maximum character count per line.
 * Splits on newlines first, then word-wraps; breaks long tokens without spaces.
 */
export function wrapThermalText(text: string, maxChars: number): string[] {
  if (maxChars < 1) return [text];
  const trimmed = text.replace(/\r\n/g, "\n");
  const lines: string[] = [];
  const paragraphs = trimmed.split("\n");

  for (let p = 0; p < paragraphs.length; p++) {
    const para = paragraphs[p];
    if (para.length === 0) {
      lines.push("");
      continue;
    }
    let remaining = para;
    while (remaining.length > 0) {
      if (remaining.length <= maxChars) {
        lines.push(remaining);
        break;
      }
      const chunk = remaining.slice(0, maxChars);
      const lastSpace = chunk.lastIndexOf(" ");
      if (lastSpace > 0) {
        lines.push(chunk.slice(0, lastSpace).trimEnd());
        remaining = remaining.slice(lastSpace + 1).trimStart();
      } else {
        lines.push(chunk);
        remaining = remaining.slice(maxChars);
      }
    }
  }
  return lines;
}
