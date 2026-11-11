package dev.docquery.api.document;

import java.text.Normalizer;

import org.springframework.util.StringUtils;

/**
 * Cleans the client-supplied filename before it is stored and shown. The name is display metadata
 * only: it never becomes part of a storage key or a filesystem path.
 */
final class FileNames {

	static final int MAX_LENGTH = 255;

	private FileNames() {
	}

	/**
	 * Drops any directory part (some browsers send {@code C:\fakepath\x.pdf}), control and
	 * bidirectional-override characters (which can disguise {@code exe.pdf} as {@code fdp.exe}), and
	 * trims to {@value #MAX_LENGTH} characters while keeping the extension.
	 * @return the cleaned name, or {@code null} if nothing usable remains
	 */
	static String sanitize(String original) {
		if (original == null) {
			return null;
		}
		String name = Normalizer.normalize(original, Normalizer.Form.NFC);
		name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
		StringBuilder clean = new StringBuilder(name.length());
		name.codePoints()
			.filter(cp -> !Character.isISOControl(cp) && !isFormatCharacter(cp))
			.forEach(clean::appendCodePoint);
		name = clean.toString().strip();
		if (!StringUtils.hasText(name) || name.equals(".") || name.equals("..")) {
			return null;
		}
		return truncate(name);
	}

	private static boolean isFormatCharacter(int codePoint) {
		return Character.getType(codePoint) == Character.FORMAT;
	}

	private static String truncate(String name) {
		if (name.codePointCount(0, name.length()) <= MAX_LENGTH) {
			return name;
		}
		int dot = name.lastIndexOf('.');
		String extension = (dot > 0 && name.length() - dot <= 16) ? name.substring(dot) : "";
		String base = name.substring(0, name.length() - extension.length());
		int keep = MAX_LENGTH - extension.codePointCount(0, extension.length());
		return base.substring(0, base.offsetByCodePoints(0, keep)) + extension;
	}

}
