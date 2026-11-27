import { jsPDF } from 'jspdf';
import type { AuditIssue, FIXED_PATCHES } from '../data/auditReport';

function sanitizePdfText(input: string): string {
  return input
    .replace(/[\u2018\u2019]/g, "'")
    .replace(/[\u201C\u201D]/g, '"')
    .replace(/\u2014/g, ' -- ')
    .replace(/\u2013/g, '-')
    .replace(/\u2026/g, '...')
    .replace(/\u00B7/g, '|')
    .replace(/\u2192/g, '->')
    .replace(/\u2190/g, '<-')
    .replace(/[^\x09\x0A\x0D\x20-\x7E\xA0-\xFF]/g, '');
}

function severityColor(severity: AuditIssue['severity']): [number, number, number] {
  if (severity === 'critical') return [185, 28, 28];
  if (severity === 'high') return [180, 83, 9];
  return [71, 85, 105];
}

export function exportAuditSummaryPdf(issues: AuditIssue[], patches: typeof FIXED_PATCHES): void {
  const doc = new jsPDF({
    orientation: 'portrait',
    unit: 'mm',
    format: 'a4'
  });

  const pageWidth = doc.internal.pageSize.getWidth();
  const pageHeight = doc.internal.pageSize.getHeight();
  const margin = 16;
  const contentWidth = pageWidth - margin * 2;
  const bottomLimit = pageHeight - 18;
  let y = margin;

  const ensureSpace = (neededMm: number) => {
    if (y + neededMm > bottomLimit) {
      doc.addPage();
      y = margin;
    }
  };

  const drawHairline = (yPos: number) => {
    doc.setDrawColor(226, 232, 240);
    doc.setLineWidth(0.3);
    doc.line(margin, yPos, pageWidth - margin, yPos);
  };

  const writeWrappedParagraph = (
    text: string,
    fontSize: number,
    color: [number, number, number],
    fontStyle: 'normal' | 'bold' = 'normal',
    lineHeight = 4.6
  ) => {
    doc.setFont('helvetica', fontStyle);
    doc.setFontSize(fontSize);
    doc.setTextColor(color[0], color[1], color[2]);
    const lines = doc.splitTextToSize(sanitizePdfText(text), contentWidth) as string[];
    for (const line of lines) {
      ensureSpace(lineHeight + 1);
      doc.text(line, margin, y);
      y += lineHeight;
    }
  };

  const writeCodeBlock = (
    label: string,
    code: string,
    labelColor: [number, number, number],
    bgColor: [number, number, number]
  ) => {
    const cleanLines = sanitizePdfText(code).split('\n');
    doc.setFont('courier', 'normal');
    doc.setFontSize(7.5);
    const wrapped: string[] = [];
    for (const rawLine of cleanLines) {
      const chunks = doc.splitTextToSize(rawLine || ' ', contentWidth - 8) as string[];
      wrapped.push(...chunks);
    }

    const lineHeight = 3.6;
    const headerHeight = 5.5;

    // Chunk across pages cleanly if code block is long
    let offset = 0;
    while (offset < wrapped.length) {
      const available = bottomLimit - y - headerHeight - 5;
      const maxLinesHere = Math.max(4, Math.floor(available / lineHeight));
      if (available < lineHeight * 4) {
        doc.addPage();
        y = margin;
        continue;
      }

      const slice = wrapped.slice(offset, offset + maxLinesHere);
      const boxHeight = slice.length * lineHeight + 5;

      doc.setFont('helvetica', 'bold');
      doc.setFontSize(8);
      doc.setTextColor(labelColor[0], labelColor[1], labelColor[2]);
      doc.text(offset === 0 ? label : `${label} (cont.)`, margin, y);
      y += 2;

      doc.setFillColor(bgColor[0], bgColor[1], bgColor[2]);
      doc.setDrawColor(203, 213, 225);
      doc.setLineWidth(0.25);
      doc.roundedRect(margin, y, contentWidth, boxHeight, 1.5, 1.5, 'FD');

      doc.setFont('courier', 'normal');
      doc.setFontSize(7.5);
      doc.setTextColor(30, 41, 59);
      let codeY = y + 4;
      for (const line of slice) {
        doc.text(line, margin + 3.5, codeY);
        codeY += lineHeight;
      }

      y += boxHeight + 4;
      offset += slice.length;
    }
  };

  // --- Cover / Top Accent Banner ---
  doc.setFillColor(0, 88, 123);
  doc.rect(0, 0, pageWidth, 4, 'F');

  y = 16;
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(19);
  doc.setTextColor(24, 34, 56);
  doc.text('A4 TV Bridge -- Bug Audit & Fix Documentation', margin, y);
  y += 6.5;

  const generatedAt = new Date().toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'long',
    day: 'numeric'
  });
  doc.setFont('helvetica', 'normal');
  doc.setFontSize(9.5);
  doc.setTextColor(70, 84, 105);
  doc.text(
    sanitizePdfText(`Repository: RatherAsif04/A4-TV-Bridge-G2  |  Generated: ${generatedAt}`),
    margin,
    y
  );
  y += 6;

  // --- Summary Metrics Row ---
  const themeCount = issues.filter((i) => i.category === 'theme').length;
  const featureCount = issues.filter((i) => i.category === 'feature').length;
  const criticalCount = issues.filter((i) => i.severity === 'critical').length;
  const highCount = issues.filter((i) => i.severity === 'high').length;
  const mediumCount = issues.filter((i) => i.severity === 'medium').length;

  const metrics = [
    { label: 'Total Bugs Fixed', value: String(issues.length) },
    { label: 'Theme & UI', value: String(themeCount) },
    { label: 'Features & ADB', value: String(featureCount) },
    { label: 'Severity (C/H/M)', value: `${criticalCount} / ${highCount} / ${mediumCount}` },
    { label: 'Patched Files', value: String(patches.length) }
  ];

  const cardGap = 3;
  const cardWidth = (contentWidth - cardGap * (metrics.length - 1)) / metrics.length;
  metrics.forEach((m, idx) => {
    const x = margin + idx * (cardWidth + cardGap);
    doc.setFillColor(248, 250, 252);
    doc.setDrawColor(226, 232, 240);
    doc.setLineWidth(0.3);
    doc.roundedRect(x, y, cardWidth, 16, 1.5, 1.5, 'FD');

    doc.setFont('helvetica', 'normal');
    doc.setFontSize(7.5);
    doc.setTextColor(100, 116, 139);
    doc.text(m.label, x + 3, y + 5.5);

    doc.setFont('helvetica', 'bold');
    doc.setFontSize(11);
    doc.setTextColor(0, 88, 123);
    doc.text(m.value, x + 3, y + 12.2);
  });
  y += 23;

  // --- Issue Overview Table ---
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(11.5);
  doc.setTextColor(24, 34, 56);
  doc.text('1. Executive Index of Identified Bugs', margin, y);
  y += 5;

  // Table header
  doc.setFillColor(241, 245, 249);
  doc.rect(margin, y, contentWidth, 6.5, 'F');
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(8);
  doc.setTextColor(51, 65, 85);
  doc.text('#', margin + 2, y + 4.4);
  doc.text('Severity', margin + 10, y + 4.4);
  doc.text('Category', margin + 27, y + 4.4);
  doc.text('Issue Title', margin + 52, y + 4.4);
  y += 7;

  issues.forEach((issue, idx) => {
    const titleLines = doc.splitTextToSize(sanitizePdfText(issue.title), contentWidth - 54) as string[];
    const rowHeight = Math.max(6, titleLines.length * 3.8 + 2.2);
    ensureSpace(rowHeight + 1);

    doc.setFont('courier', 'normal');
    doc.setFontSize(8);
    doc.setTextColor(100, 116, 139);
    doc.text(String(idx + 1).padStart(2, '0'), margin + 2, y + 3.8);

    const [sr, sg, sb] = severityColor(issue.severity);
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(7.8);
    doc.setTextColor(sr, sg, sb);
    doc.text(issue.severity.toUpperCase(), margin + 10, y + 3.8);

    doc.setFont('helvetica', 'normal');
    doc.setTextColor(71, 85, 105);
    doc.text(issue.category === 'theme' ? 'Theme & UI' : 'Feature / ADB', margin + 27, y + 3.8);

    doc.setTextColor(24, 34, 56);
    let ty = y + 3.8;
    for (const line of titleLines) {
      doc.text(line, margin + 52, ty);
      ty += 3.8;
    }

    y += rowHeight;
    drawHairline(y);
  });

  y += 9;

  // --- Detailed Bug & Fix Breakdown ---
  ensureSpace(14);
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(11.5);
  doc.setTextColor(24, 34, 56);
  doc.text('2. Detailed Bug Analysis & Implemented Fixes', margin, y);
  y += 6;

  issues.forEach((issue, idx) => {
    ensureSpace(36);

    // Issue Header Bar
    doc.setFillColor(248, 250, 252);
    doc.setDrawColor(203, 213, 225);
    doc.setLineWidth(0.3);
    const headerTitle = `${String(idx + 1).padStart(2, '0')}. ${sanitizePdfText(issue.title)}`;
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(10.5);
    const titleWrapped = doc.splitTextToSize(headerTitle, contentWidth - 6) as string[];
    const barHeight = titleWrapped.length * 4.8 + 8.5;

    doc.roundedRect(margin, y, contentWidth, barHeight, 1.5, 1.5, 'FD');
    doc.setFillColor(0, 88, 123);
    doc.rect(margin, y, 1.6, barHeight, 'F');

    doc.setTextColor(24, 34, 56);
    let hy = y + 5.2;
    for (const line of titleWrapped) {
      doc.text(line, margin + 4, hy);
      hy += 4.8;
    }

    const [sr, sg, sb] = severityColor(issue.severity);
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(7.8);
    doc.setTextColor(sr, sg, sb);
    const sevText = issue.severity.toUpperCase();
    doc.text(sevText, margin + 4, hy + 1);

    doc.setFont('courier', 'normal');
    doc.setFontSize(7.8);
    doc.setTextColor(71, 85, 105);
    const metaText = sanitizePdfText(
      `  |  ${issue.category === 'theme' ? 'Theme & UI' : 'Feature / ADB'}  |  ${issue.file} (${issue.lines})`
    );
    doc.text(metaText, margin + 4 + doc.getTextWidth(sevText) + 1.5, hy + 1);

    y += barHeight + 4.5;

    // Summary
    writeWrappedParagraph('Summary', 8.5, [0, 88, 123], 'bold', 4);
    writeWrappedParagraph(issue.summary, 9, [30, 41, 59], 'normal', 4.4);
    y += 2;

    // Root Cause
    writeWrappedParagraph('Root Cause', 8.5, [185, 28, 28], 'bold', 4);
    writeWrappedParagraph(issue.rootCause, 8.8, [51, 65, 85], 'normal', 4.3);
    y += 2;

    // Implemented Fix
    writeWrappedParagraph('Implemented Fix', 8.5, [21, 128, 61], 'bold', 4);
    writeWrappedParagraph(issue.fixSummary, 8.8, [51, 65, 85], 'normal', 4.3);
    y += 3;

    // Before / After Code Snippets
    writeCodeBlock('Before (v1.0.0)', issue.beforeCode, [185, 28, 28], [254, 242, 242]);
    writeCodeBlock('After (Corrected)', issue.afterCode, [21, 128, 61], [240, 253, 244]);

    y += 4;
  });

  // --- Patched Files Reference ---
  if (patches.length > 0) {
    ensureSpace(24);
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(11.5);
    doc.setTextColor(24, 34, 56);
    doc.text('3. Patched Source Files Summary', margin, y);
    y += 6;

    patches.forEach((patch, idx) => {
      ensureSpace(10);
      doc.setFont('courier', 'bold');
      doc.setFontSize(8.5);
      doc.setTextColor(0, 88, 123);
      doc.text(`${String(idx + 1).padStart(2, '0')}. ${sanitizePdfText(patch.path)}`, margin, y);
      y += 4;

      doc.setFont('helvetica', 'normal');
      doc.setFontSize(8);
      doc.setTextColor(71, 85, 105);
      doc.text(
        sanitizePdfText(`Addresses ${patch.issueIds.length} issue(s): ${patch.issueIds.join(', ')}`),
        margin + 5,
        y
      );
      y += 5;
      drawHairline(y - 1.5);
    });
  }

  // --- Running Footer on All Pages ---
  const totalPages = doc.getNumberOfPages();
  for (let p = 1; p <= totalPages; p++) {
    doc.setPage(p);
    drawHairline(pageHeight - 13);
    doc.setFont('helvetica', 'normal');
    doc.setFontSize(8);
    doc.setTextColor(100, 116, 139);
    doc.text('A4 TV Bridge -- Code Audit & Fix Documentation', margin, pageHeight - 8);
    doc.text(`Page ${p} of ${totalPages}`, pageWidth - margin, pageHeight - 8, { align: 'right' });
  }

  doc.save('A4-TV-Bridge-Code-Audit-Summary.pdf');
}
