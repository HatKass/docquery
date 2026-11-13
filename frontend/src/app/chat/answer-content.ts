import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { formatAnswer } from './answer-format';
import { citationLabel } from './answer-segments';
import { Citation } from './chat.model';

/**
 * Renders an answer's safe Markdown subset (see {@link formatAnswer}) with clickable citation chips.
 * Everything is bound as text: no `innerHTML` anywhere.
 */
@Component({
  selector: 'app-answer-content',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @for (block of blocks(); track $index) {
      @switch (block.kind) {
        @case ('paragraph') {
          <p class="whitespace-pre-line [&:not(:first-child)]:mt-2">
            <ng-container *ngTemplateOutlet="inline; context: { $implicit: block.inlines }" />
          </p>
        }
        @case ('heading') {
          <p class="font-semibold [&:not(:first-child)]:mt-3">
            <ng-container *ngTemplateOutlet="inline; context: { $implicit: block.inlines }" />
          </p>
        }
        @case ('list') {
          @if (block.ordered) {
            <ol class="list-decimal space-y-1 pl-5 [&:not(:first-child)]:mt-2" [attr.start]="block.start">
              @for (item of block.items; track $index) {
                <li><ng-container *ngTemplateOutlet="inline; context: { $implicit: item }" /></li>
              }
            </ol>
          } @else {
            <ul class="list-disc space-y-1 pl-5 [&:not(:first-child)]:mt-2">
              @for (item of block.items; track $index) {
                <li><ng-container *ngTemplateOutlet="inline; context: { $implicit: item }" /></li>
              }
            </ul>
          }
        }
      }
    }

    <ng-template #inline let-items>
      @for (s of items; track $index) {
        @switch (s.kind) {
          @case ('text') {<span>{{ s.text }}</span>}
          @case ('code') {<code class="rounded bg-slate-200/70 px-1 py-0.5 font-mono text-[0.85em]">{{ s.text }}</code>}
          @case ('bold') {
            <strong class="font-semibold">
              @for (c of s.children; track $index) {
                @if (c.kind === 'text') {<span>{{ c.text }}</span>} @else {
                  <ng-container *ngTemplateOutlet="chip; context: { $implicit: c.citation }" />
                }
              }
            </strong>
          }
          @case ('citation') {
            <ng-container *ngTemplateOutlet="chip; context: { $implicit: s.citation }" />
          }
        }
      }
    </ng-template>

    <ng-template #chip let-citation>
      <button
        type="button"
        class="mx-0.5 inline-flex h-5 min-w-5 items-center justify-center rounded bg-indigo-100 px-1 align-text-top text-xs font-semibold text-indigo-800 hover:bg-indigo-200"
        [class.ring-2]="selected() === citation"
        [title]="label(citation)"
        [attr.aria-label]="'Source ' + citation.sourceNumber + ': ' + label(citation)"
        data-testid="citation-chip"
        (click)="citationSelected.emit(citation)"
      >
        {{ citation.sourceNumber }}
      </button>
    </ng-template>
  `,
  imports: [NgTemplateOutlet],
})
export class AnswerContent {
  readonly content = input.required<string>();
  readonly citations = input<readonly Citation[]>([]);
  readonly selected = input<Citation | null>(null);
  readonly citationSelected = output<Citation>();

  protected readonly blocks = computed(() => formatAnswer(this.content(), this.citations()));

  protected label(citation: Citation): string {
    return citationLabel(citation);
  }
}
