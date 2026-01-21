package com.iLovePdf.conversion.service.impl;

import com.iLovePdf.conversion.service.FilesStorageService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@Service
public class FilesStorageServiceImpl implements FilesStorageService {

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

            return target.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file: " + e.getMessage(), e);
        }
    }

    private String extractTextFromPdf(String pdfPath) {
        Path path = Paths.get(pdfPath);
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document).trim();
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract text from PDF: " + e.getMessage(), e);
        }
    }

    @Override
    public File convertToWord(String pdfPath) {
        String text = extractTextFromPdf(pdfPath);
        File outputFile = new File(pdfPath.replace(".pdf", ".docx"));

        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream out = new FileOutputStream(outputFile)) {

            XWPFParagraph para = doc.createParagraph();
            para.createRun().setText(text);
            doc.write(out);
        } catch (IOException e) {
            throw new RuntimeException("Failed to convert to Word: " + e.getMessage(), e);
        }

        return outputFile;
    }

    @Override
    public File convertToMarkdown(String pdfPath) {
        String text = extractTextFromPdf(pdfPath);
        text = text.replaceAll("\r\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();

        File outputFile = new File(pdfPath.replace(".pdf", ".md"));

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            writer.write(text);
        } catch (IOException e) {
            throw new RuntimeException("Failed to convert to Markdown: " + e.getMessage(), e);
        }

        return outputFile;
    }

    @Override
    public File convertToJson(String pdfPath) {
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
        } catch (IOException e) {
            throw new RuntimeException("Failed to convert to JSON: " + e.getMessage(), e);
        }

        return outputFile;
    }

    @Override
    public File convertToCsv(String pdfPath) {
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
                    writer.println(); // empty line between tables
                }
            }

            oe.close();

            // Improved fallback: write full text line-by-line as single-column CSV
            if (!foundAnyTable) {
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

        } catch (IOException e) {
            throw new RuntimeException("Failed to convert to CSV: " + e.getMessage(), e);
        }

        return outputFile;
    }

    @Override
    public File convertToJpg(String pdfPath) {
        Path pdfFilePath = Paths.get(pdfPath);
        String baseName = pdfFilePath.getFileName().toString().replace(".pdf", "");

        // We'll create a folder for the JPGs: ./uploads/original-filename-images/
        Path outputDir = pdfFilePath.getParent().resolve(baseName + "-images");
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output directory for JPGs: " + e.getMessage(), e);
        }

        File firstOutputFile = null; // We'll return the first page as the "main" download file

        try (PDDocument document = Loader.loadPDF(pdfFilePath.toFile())) {

            PDFRenderer pdfRenderer = new PDFRenderer(document);
            int dpi = 150; // 150–300 is good balance (higher = bigger file & better quality)

            for (int page = 0; page < document.getNumberOfPages(); page++) {
                BufferedImage bim = pdfRenderer.renderImageWithDPI(page, dpi, ImageType.RGB);

                // Output filename: page-1.jpg, page-2.jpg, ...
                String imageName = String.format("%s-page-%d.jpg", baseName, page + 1);
                File outputFile = outputDir.resolve(imageName).toFile();
                ImageIO.write(bim, "jpg", outputFile);

                if (page == 0) {
                    firstOutputFile = outputFile; // We return the first page for download
                }
            }

            if (firstOutputFile == null) {
                throw new RuntimeException("No pages found in PDF");
            }

            return firstOutputFile;

        } catch (IOException e) {
            throw new RuntimeException("Failed to convert PDF to JPG: " + e.getMessage(), e);
        }
    }

    @Override
    public File mergePdfs(List<String> pdfPaths) {
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

            return outputFile;

        } catch (IOException e) {
            outputFile.delete();
            throw new RuntimeException("Failed to merge PDFs: " + e.getMessage(), e);
        }
    }
}