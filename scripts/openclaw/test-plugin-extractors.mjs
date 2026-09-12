import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

// Explicit synthetic input only. Does not scan owner documents or call a model.
const [packagePath, pdfPath] = process.argv.slice(2);
assert(packagePath && pdfPath, 'Pass installed OpenClaw package and synthetic PDF paths');
const load = relative => import(pathToFileURL(join(packagePath, 'dist/extensions', relative)));
const { createReadabilityWebContentExtractor } = await load('web-readability/web-content-extractor.js');
const { createPdfDocumentExtractor } = await load('document-extract/document-extractor.js');
const article = '이번 시험은 한국어 기사 본문을 읽는 기능을 확인합니다. 본문에 있는 결정과 근거를 보존하고 메뉴와 광고는 제외합니다. 문서와 웹의 출처를 확인한 뒤 내용을 요약합니다. ';
const html = `<html lang="ko"><head><title>한국어 기사 시험</title></head><body>
<nav><a href="/login">NAV_ONLY_LOGIN</a></nav><main><article><h1>한국어 기사 시험</h1>
${Array.from({length: 5}, () => `<p>${article.repeat(3)}</p>`).join('')}
</article></main><footer>FOOTER_ONLY</footer></body></html>`;
const web = await createReadabilityWebContentExtractor().extract({html,
    url: 'https://example.com/synthetic-korean-article', extractMode: 'markdown'});
assert.equal(web.title, '한국어 기사 시험');
assert(web.text.includes('결정과 근거를 보존'));
assert(!web.text.includes('NAV_ONLY_LOGIN') && !web.text.includes('FOOTER_ONLY'));
const extractor = createPdfDocumentExtractor();
const request = {buffer: new Uint8Array(readFileSync(pdfPath)), maxPages: 2,
    minTextChars: 200, maxPixels: 4_000_000};
const pdf = await extractor.extract(request);
assert(pdf.text.includes('검증 완료') && pdf.text.includes('두 번째 페이지'));
assert.equal(pdf.images.length, 0);
const rendered = await extractor.extract({...request, minTextChars: 1_000_000});
assert.equal(rendered.images.length, 2);
let pixels = 0;
for (const image of rendered.images) {
    assert.equal(image.mimeType, 'image/png');
    const bytes = Buffer.from(image.data, 'base64');
    assert.equal(bytes.subarray(1, 4).toString(), 'PNG');
    pixels += bytes.readUInt32BE(16) * bytes.readUInt32BE(20);
}
assert(pixels <= request.maxPixels);
await assert.rejects(extractor.extract({...request, pageNumbers: [999]}), /No requested PDF pages/);
console.log(JSON.stringify({koreanArticle: true, navigationRemoved: true, koreanPdfText: true,
    fallbackImages: rendered.images.length, imagePixels: pixels, invalidPageRejected: true}));
