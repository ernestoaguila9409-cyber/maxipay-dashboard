/**
 * Thermal receipt line width — matches Android `ReceiptSettings.lineWidthForSize`:
 * `LINE_WIDTH` = 48 (Normal / Large), `LINE_WIDTH_WIDE` = 24 (X-Large / double-width).
 */
export function thermalCharsPerLine(fontSizeSetting: number): number {
  return fontSizeSetting === 2 ? 24 : 48;
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
