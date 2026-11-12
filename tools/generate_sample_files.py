"""
Generates real sample documents across all supported SLATE formats in ../sample_documents/
for testing on Android phones and verifying format compatibility.
"""
import os
import csv
from docx import Document as DocxDocument
import openpyxl
from pptx import Presentation
from pptx.util import Inches, Pt
import xlwt
from fpdf import FPDF
from odf.opendocument import OpenDocumentText, OpenDocumentSpreadsheet, OpenDocumentPresentation
from odf.text import P, H
from odf.table import Table, TableCell, TableRow
from odf.draw import Page, Frame, TextBox

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "sample_documents")
os.makedirs(OUT, exist_ok=True)


def make_pdf():
    pdf = FPDF()
    pdf.add_page()
    pdf.set_font("Helvetica", "B", 18)
    pdf.cell(0, 12, "SLATE Sample PDF Document", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 12)
    pdf.multi_cell(
        0, 8,
        "This is a multi-page PDF document generated to test SLATE's PDF viewer, "
        "full-text search, text selection, highlighting, freehand ink annotations, "
        "sticky comments, signatures, page rotation, splitting, merging, and compression."
    )
    pdf.add_page()
    pdf.set_font("Helvetica", "B", 16)
    pdf.cell(0, 12, "Page 2 - Financial Overview", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 12)
    pdf.multi_cell(0, 8, "Second page content for testing page navigation and Continue Reading.")
    pdf.output(os.path.join(OUT, "Sample_Report.pdf"))


def make_docx():
    doc = DocxDocument()
    doc.add_heading("SLATE Word Document (DOCX)", 0)
    p = doc.add_paragraph("Every document. One place. ")
    r = p.add_run("Bold and italic text run.")
    r.bold = True
    r.italic = True
    doc.add_heading("Section 1: Feature Table", level=2)
    tbl = doc.add_table(rows=3, cols=2)
    tbl.cell(0, 0).text = "Feature"
    tbl.cell(0, 1).text = "Supported"
    tbl.cell(1, 0).text = "Offline Editing"
    tbl.cell(1, 1).text = "Yes"
    tbl.cell(2, 0).text = "PDF Export"
    tbl.cell(2, 1).text = "Yes"
    doc.save(os.path.join(OUT, "Sample_Document.docx"))


def make_odt():
    textdoc = OpenDocumentText()
    h = H(outlinelevel=1, text="SLATE OpenDocument Text (ODT)")
    textdoc.text.addElement(h)
    p = P(text="This ODT file tests OpenDocument reading, editing, and conversion.")
    textdoc.text.addElement(p)
    textdoc.save(os.path.join(OUT, "Sample_Document.odt"))


def make_rtf_and_txt():
    rtf = (
        r"{\rtf1\ansi\deff0{\fonttbl{\f0 Sans-Serif;}}"
        r"\pard\b\fs32 SLATE Rich Text Document (RTF)\b0\fs24\par"
        r"\pard This document tests RTF formatting, bold, italic, and conversion.\par}"
    )
    with open(os.path.join(OUT, "Sample_Document.rtf"), "w", encoding="ascii") as f:
        f.write(rtf)
    with open(os.path.join(OUT, "Sample_Notes.txt"), "w", encoding="utf-8") as f:
        f.write("SLATE Plain Text Notes\nLine 2: Offline-first Android document workspace.\n")


def make_xlsx_xls_ods_csv():
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Budget"
    ws.append(["Category", "Q1", "Q2"])
    ws.append(["Research", 1200, 1500])
    ws.append(["Development", 2800, 3100])
    ws.append(["Total", "=SUM(B2:B3)", "=SUM(C2:C3)"])
    wb.save(os.path.join(OUT, "Sample_Spreadsheet.xlsx"))

    xls_wb = xlwt.Workbook()
    xls_ws = xls_wb.add_sheet("LegacyBudget")
    xls_ws.write(0, 0, "Category")
    xls_ws.write(0, 1, "Amount")
    xls_ws.write(1, 0, "Hardware")
    xls_ws.write(1, 1, 950.5)
    xls_ws.write(2, 0, "Software")
    xls_ws.write(2, 1, 1450.0)
    xls_wb.save(os.path.join(OUT, "Sample_Legacy.xls"))

    ods = OpenDocumentSpreadsheet()
    table = Table(name="Metrics")
    for row_vals in [["Metric", "Value"], ["Users", "1000"], ["CrashFree", "99.9"]]:
        tr = TableRow()
        for val in row_vals:
            tc = TableCell(valuetype="string")
            tc.addElement(P(text=val))
            tr.addElement(tc)
        table.addElement(tr)
    ods.spreadsheet.addElement(table)
    ods.save(os.path.join(OUT, "Sample_Spreadsheet.ods"))

    with open(os.path.join(OUT, "Sample_Data.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["ID", "Name", "Score"])
        w.writerow(["1", "Alpha", "95"])
        w.writerow(["2", "Beta", "88"])


def make_pptx_odp():
    prs = Presentation()
    slide_layout = prs.slide_layouts[0]
    slide = prs.slides.add_slide(slide_layout)
    slide.shapes.title.text = "SLATE Presentation (PPTX)"
    slide.placeholders[1].text = "Every document. One place.\n100% Offline & Ad-Free."
    prs.save(os.path.join(OUT, "Sample_Presentation.pptx"))

    odp = OpenDocumentPresentation()
    page = Page(name="Slide1", masterpagename="Default")
    frame = Frame(width="22cm", height="6cm", x="3cm", y="3cm")
    tb = TextBox()
    tb.addElement(P(text="SLATE OpenDocument Presentation (ODP)"))
    tb.addElement(P(text="Native slide viewing, editing, and PDF export."))
    frame.addElement(tb)
    page.addElement(frame)
    odp.presentation.addElement(page)
    odp.save(os.path.join(OUT, "Sample_Presentation.odp"))


if __name__ == "__main__":
    make_pdf()
    make_docx()
    make_odt()
    make_rtf_and_txt()
    make_xlsx_xls_ods_csv()
    make_pptx_odp()
    print("Generated sample documents in", OUT)
