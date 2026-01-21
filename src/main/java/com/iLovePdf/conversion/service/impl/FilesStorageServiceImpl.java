package com.iLovePdf.conversion.service.impl;

import com.iLovePdf.conversion.service.FilesStorageService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.BasicExtractionAlgorithm;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.List;

@Service
public class FilesStorageServiceImpl implements FilesStorageService {

    private static final Logger logger = LoggerFactory.getLogger(FilesStorageServiceImpl.class);

    private final Path root = Paths.get("./uploads").toAbsolutePath().normalize();

    @Override
    public String save(MultipartFile file) {
        try {
            if (file.isEmpty()) {
                throw new IllegalArgumentException("File is empty");
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                originalFilename = "uploaded_" + System.currentTimeMillis() + ".pdf";
            }

            String safeFilename = originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
            Path target = root.resolve(safeFilename);

            Files.createDirectories(target.getParent());
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

            logger.info("File saved at {}", target);
            return target.toString();

        } catch (IOException e) {
            logger.error("Failed to store file", e);
            throw new RuntimeException("Failed to store file: " + e.getMessage(), e);
        }
    }

    private String extractTextFromPdf(String pdfPath) {
        Path path = Paths.get(pdfPath);
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document).trim();
            logger.info("Extracted text from PDF: {}", pdfPath);
            return text;
        } catch (IOException e) {
            logger.error("Failed to extract text from PDF: {}", pdfPath, e);
            throw new RuntimeException("Failed to extract text from PDF: " + e.getMessage(), e);
        }
    }

    @Override
    public File convertToWord(String pdfPath) {
        logger.info("Converting PDF to Word: {}", pdfPath);

        String text = extractTextFromPdf(pdfPath);
        File outputFile = new File(pdfPath.replace(".pdf", ".docx"));

        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream out = new FileOutputStream(outputFile)) {

            XWPFParagraph para = doc.createParagraph();
            para.createRun().setText(text);
            doc.write(out);

            logger.info("Word file created: {}", outputFile.getAbsolutePath());
            return outputFile;

        } catch (IOException e) {
            logger.error("Failed to convert PDF to Word: {}", pdfPath, e);
            throw new RuntimeException("Failed to convert to Word: " + e.getMessage(), e);
        }
    }

    @Override
    public File convertToMarkdown(String pdfPath) {
        logger.info("Converting PDF to Markdown: {}", pdfPath);

        String text = extractTextFromPdf(pdfPath);
        text = text.replaceAll("\r\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();

        File outputFile = new File(pdfPath.replace(".pdf", ".md"));

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            writer.write(text);
            logger.info("Markdown file created: {}", outputFile.getAbsolutePath());
            return outputFile;

        } catch (IOException e) {
            logger.error("Failed to convert PDF to Markdown: {}", pdfPath, e);
            throw new RuntimeException("Failed to convert to Markdown: " + e.getMessage(), e);
        }
    }

    @Override
    public File convertToJson(String pdfPath) {
        logger.info("Converting PDF to JSON: {}", pdfPath);

        String text = extractTextFromPdf(pdfPath);
        String escaped = text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");

        String jsonContent = "{\"extractedText\": \"" + escaped + "\"}";
        File outputFile = new File(pdfPath.replace(".pdf", ".json"));

        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write(jsonContent);
            logger.info("JSON file created: {}", outputFile.getAbsolutePath());
            return outputFile;

        } catch (IOException e) {
            logger.error("Failed to convert PDF to JSON: {}", pdfPath, e);
            throw new RuntimeException("Failed to convert to JSON: " + e.getMessage(), e);
        }
    }

    @Override
    public File convertToCsv(String pdfPath) {
        logger.info("Converting PDF to CSV: {}", pdfPath);

        File outputFile = new File(pdfPath.replace(".pdf", ".csv"));

        try (PDDocument document = Loader.loadPDF(new File(pdfPath));
             PrintWriter writer = new PrintWriter(outputFile)) {

            ObjectExtractor oe = new ObjectExtractor(document);
            BasicExtractionAlgorithm bea = new BasicExtractionAlgorithm();

            boolean foundAnyTable = false;

            for (int pageNum = 1; pageNum <= document.getNumberOfPages(); pageNum++) {
                Page page = oe.extract(pageNum);
                List<Table> tables = bea.extract(page);

                if (!tables.isEmpty()) {
                    foundAnyTable = true;
                }

                for (Table table : tables) {
                    List<List<RectangularTextContainer>> rows = table.getRows();
                    for (List<RectangularTextContainer> cells : rows) {
                        for (int i = 0; i < cells.size(); i++) {
                            String cellText = cells.get(i).getText().trim().replace("\"", "\"\"");
                            writer.print("\"" + cellText + "\"");
                            if (i < cells.size() - 1) {
                                writer.print(",");
                            }
                        }
                        writer.println();
                    }
                    writer.println();
                }
            }

            oe.close();

            if (!foundAnyTable) {
                logger.warn("No tables detected in PDF, using fallback text extraction: {}", pdfPath);

                String fallbackText = extractTextFromPdf(pdfPath);
                writer.println("\"Extracted Text (No Tables Detected)\"");
                String[] lines = fallbackText.split("\n");
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        writer.println("\"" + trimmed.replace("\"", "\"\"") + "\"");
                    }
                }
            }

            logger.info("CSV file created: {}", outputFile.getAbsolutePath());
            return outputFile;

        } catch (IOException e) {
            logger.error("Failed to convert PDF to CSV: {}", pdfPath, e);
            throw new RuntimeException("Failed to convert to CSV: " + e.getMessage(), e);
        }
    }

    @Override
    public File convertToJpg(String pdfPath) {
        logger.info("Converting PDF to JPG: {}", pdfPath);

        Path pdfFilePath = Paths.get(pdfPath);
        String baseName = pdfFilePath.getFileName().toString().replace(".pdf", "");
        Path outputDir = pdfFilePath.getParent().resolve(baseName + "-images");

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            logger.error("Failed to create JPG output directory: {}", outputDir, e);
            throw new RuntimeException("Failed to create output directory for JPGs: " + e.getMessage(), e);
        }

        File firstOutputFile = null;

        try (PDDocument document = Loader.loadPDF(pdfFilePath.toFile())) {

            PDFRenderer pdfRenderer = new PDFRenderer(document);
            int dpi = 150;

            for (int page = 0; page < document.getNumberOfPages(); page++) {
                BufferedImage bim = pdfRenderer.renderImageWithDPI(page, dpi, ImageType.RGB);

                String imageName = String.format("%s-page-%d.jpg", baseName, page + 1);
                File outputFile = outputDir.resolve(imageName).toFile();
                ImageIO.write(bim, "jpg", outputFile);

                if (page == 0) {
                    firstOutputFile = outputFile;
                }
            }

            if (firstOutputFile == null) {
                throw new RuntimeException("No pages found in PDF");
            }

            logger.info("JPG images created at {}", outputDir);
            return firstOutputFile;

        } catch (IOException e) {
            logger.error("Failed to convert PDF to JPG: {}", pdfPath, e);
            throw new RuntimeException("Failed to convert PDF to JPG: " + e.getMessage(), e);
        }
    }

    @Override
    public File mergePdfs(List<String> pdfPaths) {
        logger.info("Merging {} PDF files", pdfPaths != null ? pdfPaths.size() : 0);

        if (pdfPaths == null || pdfPaths.isEmpty()) {
            throw new IllegalArgumentException("No PDF files provided for merging");
        }

        if (pdfPaths.size() == 1) {
            return new File(pdfPaths.get(0));
        }

        String baseName = "merged_" + System.currentTimeMillis();
        Path outputPath = root.resolve(baseName + ".pdf");
        File outputFile = outputPath.toFile();

        PDFMergerUtility merger = new PDFMergerUtility();
        merger.setDestinationFileName(outputFile.getAbsolutePath());

        try {
            for (String path : pdfPaths) {
                File pdfFile = new File(path);
                if (!pdfFile.exists()) {
                    throw new IOException("PDF file not found: " + path);
                }
                merger.addSource(pdfFile);
            }

            merger.mergeDocuments(null);

            logger.info("Merged PDF created: {}", outputFile.getAbsolutePath());
            return outputFile;

        } catch (IOException e) {
            logger.error("Failed to merge PDFs", e);
            outputFile.delete();
            throw new RuntimeException("Failed to merge PDFs: " + e.getMessage(), e);
        }
    }
}