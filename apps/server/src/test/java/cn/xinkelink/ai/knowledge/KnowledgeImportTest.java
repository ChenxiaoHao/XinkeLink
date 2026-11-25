package cn.xinkelink.ai.knowledge;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class KnowledgeImportTest {
 final KnowledgeImport importer=new KnowledgeImport();
 @Test void textIsPreviewedAndOriginalBytesRemainArchivable(){byte[] bytes="第一条 校对正文".getBytes(StandardCharsets.UTF_8);var result=importer.extract("policy.md",bytes);assertEquals("第一条 校对正文",result.get("body"));assertArrayEquals(bytes,Base64.getDecoder().decode((String)result.get("originalBase64")));}
 @Test void scannedPdfIsExplicitEmptyWarningAndNotFalseSuccess()throws Exception{byte[] bytes;try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){doc.addPage(new PDPage());doc.save(out);bytes=out.toByteArray();}var result=importer.extract("scan.pdf",bytes);assertEquals("",result.get("body"));assertTrue(result.get("warnings").toString().contains("没有可提取正文"));}
 @Test void docxKeepsParagraphBoundariesAndWarnsAboutImages()throws Exception{var result=importer.extract("policy.docx",docx("<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>第一条 申请表</w:t></w:r></w:p><w:p><w:r><w:t>疾病代理例外</w:t></w:r></w:p></w:body></w:document>"));assertEquals("第一条 申请表\n疾病代理例外\n",result.get("body"));assertTrue(result.get("warnings").toString().contains("图片"));}
 @Test void externalXmlEntitiesAreRejected()throws Exception{assertThrows(RuntimeException.class,()->importer.extract("attack.docx",docx("<!DOCTYPE x [<!ENTITY xxe SYSTEM 'file:///C:/Windows/win.ini'>]><w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:p>&xxe;</w:p></w:document>")));}
 @Test void unsupportedAndInvalidUtf8FilesAreRejected(){assertThrows(RuntimeException.class,()->importer.extract("file.exe",new byte[]{1}));assertThrows(RuntimeException.class,()->importer.extract("file.txt",new byte[]{(byte)0xff}));}
 @Test void pdfExtractionStopsTheWriterBeforeCollectingOversizedText()throws Exception{
  byte[] bytes=reusedPagePdf(1);
  try(var construction=mockConstruction(PDFTextStripper.class,(stripper,context)->{
   when(stripper.getText(any())).thenThrow(new AssertionError("PDF text must stream through the aggregate bound"));
   doAnswer(call->{Writer output=call.getArgument(1);output.write("A".repeat(100000).toCharArray());output.write("B".repeat(100001));fail("Oversized write must fail immediately");return null;}).when(stripper).writeText(any(),any());
  })){
   var failure=assertThrows(BusinessException.class,()->importer.extract("large.pdf",bytes));
   assertEquals(ErrorCode.VALIDATION_FAILED,failure.code());
   verify(construction.constructed().getFirst()).writeText(any(),any());
  }
 }
 @Test void pdfPageMarkersAlsoConsumeTheAggregateTextBudget()throws Exception{
  byte[] bytes=reusedPagePdf(1);
  try(var construction=mockConstruction(PDFTextStripper.class,(stripper,context)->{
   doAnswer(call->{Writer output=call.getArgument(1);output.write("A".repeat(199994));return null;}).when(stripper).writeText(any(),any());
  })){
   var failure=assertThrows(BusinessException.class,()->importer.extract("boundary.pdf",bytes));assertEquals(ErrorCode.VALIDATION_FAILED,failure.code());
  }
 }
 @Test void compressedPdfWithReusedPagesCannotBypassAggregateTextLimit()throws Exception{
  byte[] bytes=reusedPagePdf(120);assertTrue(bytes.length<50000,"Sample reuses a compressed page stream");
  var failure=assertThrows(BusinessException.class,()->importer.extract("reused.pdf",bytes));assertEquals(ErrorCode.VALIDATION_FAILED,failure.code());
 }
 private byte[] reusedPagePdf(int pages)throws Exception{
  try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){
   var page=new PDPage();doc.addPage(page);
   try(var content=new PDPageContentStream(doc,page)){content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);content.newLineAtOffset(20,700);content.showText("A".repeat(2000));content.endText();}
   for(int i=1;i<pages;i++){var copy=new PDPage();copy.setResources(page.getResources());copy.setContents(page.getContentStreams().next());doc.addPage(copy);}
   doc.save(out);return out.toByteArray();
  }
 }
 private byte[] docx(String xml)throws Exception{try(var out=new ByteArrayOutputStream();var zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write(xml.getBytes(StandardCharsets.UTF_8));zip.closeEntry();zip.finish();return out.toByteArray();}}
}
