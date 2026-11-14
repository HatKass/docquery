import { expect, test } from '@playwright/test';
import { DEMO_WORKSPACE_ID, OWNER, deleteDocument, sampleFile, signIn, uploadAndWaitUntilReady } from './support/stack';

/**
 * The main journey, end to end through every service: upload → worker pipeline with live status →
 * grounded, streamed answer with a citation → citation viewer → delete with the confirm dialog.
 */
test.describe('documents and chat', () => {
  test('upload a policy, ask about it, open the citation, then delete it', async ({ page }) => {
    const file = sampleFile('data-retention-policy.md', 'text/markdown');
    await signIn(page, OWNER);

    try {
      const row = await uploadAndWaitUntilReady(page, file);
      await expect(row).toContainText(file.name);

      await page.goto(`/workspaces/${DEMO_WORKSPACE_ID}/chat`);
      await page.getByTestId('new-conversation').click();
      // The file name is in the question, so retrieval focuses on this run's copy of the policy.
      await page
        .getByTestId('question-input')
        .fill(`According to ${file.name}, how long is customer data kept after a contract ends?`);
      await page.getByTestId('send').click();

      const answer = page.getByTestId('answer').last();
      await expect(answer).toHaveAttribute('data-status', 'COMPLETE', { timeout: 150_000 });
      await expect(answer).toContainText(/90/);

      const chip = answer.getByTestId('citation-chip').first();
      await expect(chip).toBeVisible();
      await chip.click();
      const viewer = page.getByTestId('citation-viewer');
      await expect(viewer).toBeVisible();
      await expect(viewer.getByTestId('citation-document')).toHaveText(file.name);
      await viewer.getByTestId('close-citation').click();
      // A bottom sheet on phones, a permanent column on desktop: either way the passage is gone.
      await expect(viewer.getByTestId('citation-document')).toHaveCount(0);
    } finally {
      await deleteDocument(page, file.name);
    }
  });

  test('a question the documents cannot answer is declined, not guessed', async ({ page }) => {
    await signIn(page, OWNER);
    await page.goto(`/workspaces/${DEMO_WORKSPACE_ID}/chat`);
    await page.getByTestId('new-conversation').click();
    await page.getByTestId('question-input').fill('What is the boiling point of liquid nitrogen in Kelvin?');
    await page.getByTestId('send').click();

    const answer = page.getByTestId('answer').last();
    await expect(answer).toHaveAttribute('data-status', 'COMPLETE', { timeout: 150_000 });
    await expect(answer).toContainText("I couldn't find this in your documents.");
  });
});
