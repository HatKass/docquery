package dev.docquery.api.document;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The file types DocQuery accepts, with the extensions that may carry them. */
public enum DocumentType {

	PDF("application/pdf", Set.of("pdf")),

	DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", Set.of("docx")),

	TEXT("text/plain", Set.of("txt")),

	MARKDOWN("text/markdown", Set.of("md", "markdown"));

	private final String mediaType;

	private final Set<String> extensions;

	DocumentType(String mediaType, Set<String> extensions) {
		this.mediaType = mediaType;
		this.extensions = extensions;
	}

	public String mediaType() {
		return this.mediaType;
	}

	public boolean isText() {
		return this == TEXT || this == MARKDOWN;
	}

	static Optional<DocumentType> forExtension(String extension) {
		return Arrays.stream(values()).filter(type -> type.extensions.contains(extension)).findFirst();
	}

	/** All accepted extensions, e.g. for the upload dialog's {@code accept} attribute. */
	static List<String> allExtensions() {
		return Arrays.stream(values()).flatMap(type -> type.extensions.stream()).sorted().toList();
	}

}
