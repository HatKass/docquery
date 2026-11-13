import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { DocumentApi } from './document-api.service';

/** What is needed to open a stored document. */
export interface OpenableDocument {
  id: string;
  filename: string;
  contentType: string;
}

/**
 * Opens a document's original file: PDFs and text in a new tab (the browser's own viewer), Word
 * files as a download. The file is fetched with the bearer token, so it cannot be a plain link.
 */
@Injectable({ providedIn: 'root' })
export class DocumentOpener {
  private readonly api = inject(DocumentApi);

  /**
   * @param page for PDFs, the 1-based page to open at (`#page=N`, honored by the PDF viewers of
   * Chrome, Edge, Firefox and Safari)
   * @throws whatever the download threw (the caller shows it)
   */
  async open(workspaceId: string, doc: OpenableDocument, page?: number | null): Promise<void> {
    const pdf = doc.contentType === 'application/pdf';
    const viewable = pdf || doc.contentType.startsWith('text/');
    // Open the tab synchronously, inside the click, so popup blockers allow it.
    const tab = viewable ? window.open('', '_blank') : null;
    try {
      const blob = await firstValueFrom(this.api.content(workspaceId, doc.id));
      const type = doc.contentType.startsWith('text/') ? 'text/plain;charset=utf-8' : doc.contentType;
      const url = URL.createObjectURL(new Blob([blob], { type }));
      if (tab) {
        tab.location.href = pdf && page ? `${url}#page=${page}` : url;
      } else {
        const link = document.createElement('a');
        link.href = url;
        link.download = doc.filename;
        link.click();
      }
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      tab?.close();
      throw e;
    }
  }
}
