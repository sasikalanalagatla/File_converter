package com.iLovePdf.conversion.service.impl;

import com.iLovePdf.conversion.service.FilesStorageService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
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
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class FilesStorageServiceImpl implements FilesStorageService {

    private static final Logger logger = LoggerFactory.getLogger(FilesStorageServiceImpl.class);

    @Override
    public String save(MultipartFile file) {
        try {
            if (file.isEmpty()) {
                throw new IllegalArgumentException("File is empty");
            }

            Path tempFile = Files.createTempFile("pdf-upload-", ".pdf");
            file.transferTo(tempFile.toFile());

            logger.info("Uploaded PDF saved temporarily: {}", tempFile.toAbsolutePath());
            return tempFile.toAbsolutePath().toString();

        } catch (IOException e) {
            logger.error("Failed to save uploaded file", e);
            throw new RuntimeException("Failed to store file: " + e.getMessage(), e);
        }
    }

    private String extractTextFromPdf(String pdfPath) {
        try (PDDocument document = Loader.loadPDF(new File(pdfPath))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document).trim();
            logger.debug("Extracted {} characters from PDF", text.length());
            return text;
        } catch (IOException e) {
            logger.error("Failed to extract text from PDF: {}", pdfPath, e);
            throw new RuntimeException("Failed to extract text", e);
        }
    }

    @Override
    public byte[] convertToWordBytes(String pdfPath) throws IOException {
        String text = extractTextFromPdf(pdfPath);

        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            XWPFParagraph para = doc.createParagraph();
            para.createRun().setText(text);
            doc.write(baos);

            return baos.toByteArray();
        }
    }

    @Override
    public byte[] convertToMarkdownBytes(String pdfPath) throws IOException {
        String text = extractTextFromPdf(pdfPath);
        text = text.replaceAll("\r\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();

        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] convertToJsonBytes(String pdfPath) throws IOException {
        String text = extractTextFromPdf(pdfPath);
        String escaped = text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");

        String json = "{\"extractedText\": \"" + escaped + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] convertToCsvBytes(String pdfPath) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PDDocument document = Loader.loadPDF(new File(pdfPath));
             PrintWriter writer = new PrintWriter(baos)) {

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

            writer.flush();
            return baos.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("CSV conversion failed", e);
        }
    }

    @Override
    public byte[] convertToJpgBytes(String pdfPath) throws IOException {
        logger.info("Converting PDF to JPG ZIP (in-memory): {}", pdfPath);

        List<Path> tempImages = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(new File(pdfPath))) {
            PDFRenderer renderer = new PDFRenderer(document);
            int dpi = 150;

            for (int page = 0; page < document.getNumberOfPages(); page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, dpi, ImageType.RGB);
                Path tempImg = Files.createTempFile("jpg-page-" + (page + 1) + "-", ".jpg");
                ImageIO.write(image, "jpg", tempImg.toFile());
                tempImages.add(tempImg);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                for (Path imgPath : tempImages) {
                    zos.putNextEntry(new ZipEntry(imgPath.getFileName().toString()));
                    Files.copy(imgPath, zos);
                    zos.closeEntry();
                }
            }

            byte[] zipBytes = baos.toByteArray();

            for (Path p : tempImages) {
                Files.deleteIfExists(p);
            }

            logger.info("ZIP created in memory ({} bytes for {} pages)", zipBytes.length, document.getNumberOfPages());
            return zipBytes;

        } catch (IOException e) {
            for (Path p : tempImages) Files.deleteIfExists(p);
            throw e;
        }
    }

    @Override
    public byte[] mergePdfsBytes(List<String> pdfPaths) throws IOException {
        Path tempMerged = Files.createTempFile("merged-", ".pdf");
        try {
            PDFMergerUtility merger = new PDFMergerUtility();
            merger.setDestinationFileName(tempMerged.toAbsolutePath().toString());

            for (String path : pdfPaths) {
                merger.addSource(new File(path));
            }

            merger.mergeDocuments(null);

            byte[] bytes = Files.readAllBytes(tempMerged);
            Files.deleteIfExists(tempMerged);
            return bytes;

        } catch (IOException e) {
            Files.deleteIfExists(tempMerged);
            throw e;
        }
    }
    @Override
    public Map<String, Object> processFiles(MultipartFile[] files, String format) {

        List<Path> uploadedPaths = new ArrayList<>();

        try {
            if (files == null || files.length == 0) {
                throw new IllegalArgumentException("Please upload at least one file!");
            }

            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) continue;

                String filename = file.getOriginalFilename();
                if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
                    cleanupUploadedFiles(uploadedPaths);
                    throw new IllegalArgumentException("Only PDF files are allowed!");
                }

                String savedPath = save(file);
                uploadedPaths.add(Path.of(savedPath));
                logger.info("Saved uploaded PDF temporarily: {}", savedPath);
            }

            if (uploadedPaths.isEmpty()) {
                throw new IllegalArgumentException("No valid PDF files uploaded!");
            }

            byte[] resultBytes;
            String resultFilename;

            if (uploadedPaths.size() > 1) {
                resultBytes = mergePdfsBytes(
                        uploadedPaths.stream().map(Path::toString).toList());
                resultFilename = "merged.pdf";
            } else {
                String pdfPath = uploadedPaths.get(0).toString();

                if (format == null || format.trim().isEmpty()) {
                    throw new IllegalArgumentException("Please select a format!");
                }

                switch (format.toLowerCase()) {
                    case "jpg" -> {
                        resultBytes = convertToJpgBytes(pdfPath);
                        resultFilename = "pdf-pages.zip";
                    }
                    case "word" -> {
                        resultBytes = convertToWordBytes(pdfPath);
                        resultFilename = "converted.docx";
                    }
                    case "markdown" -> {
                        resultBytes = convertToMarkdownBytes(pdfPath);
                        resultFilename = "converted.md";
                    }
                    case "json" -> {
                        resultBytes = convertToJsonBytes(pdfPath);
                        resultFilename = "converted.json";
                    }
                    case "csv" -> {
                        resultBytes = convertToCsvBytes(pdfPath);
                        resultFilename = "converted.csv";
                    }
                    case "merge" -> {
                        resultBytes = Files.readAllBytes(Path.of(pdfPath));
                        resultFilename = "original.pdf";
                    }
                    case "compress" -> {
                        resultBytes = compressPdfBytes(pdfPath);
                        resultFilename = "compressed.pdf";
                    }
                    default -> throw new IllegalArgumentException("Invalid format: " + format);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("bytes", resultBytes);
            result.put("filename", resultFilename);

            return result;

        } catch (Exception e) {
            cleanupUploadedFiles(uploadedPaths);
            throw new RuntimeException(e.getMessage(), e);

        } finally {
            cleanupUploadedFiles(uploadedPaths);
        }
    }
    @Override
    public byte[] compressPdfBytes(String pdfPath) throws IOException {
        logger.info("Starting PDF compression: {}", pdfPath);
        long originalSize = Files.size(Path.of(pdfPath));

        Path tempOut = Files.createTempFile("compressed-", ".pdf");

        try (PDDocument doc = Loader.loadPDF(new File(pdfPath))) {

            float jpegQuality = 0.70f;
            int targetMaxDimension = 1200;

            boolean anyChange = false;

            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                if (res == null) continue;

                Iterable<COSName> xobjNames = res.getXObjectNames();
                if (xobjNames == null) continue;

                for (COSName name : xobjNames) {
                    PDXObject xobj = res.getXObject(name);
                    if (!(xobj instanceof PDImageXObject)) continue;

                    PDImageXObject img = (PDImageXObject) xobj;

                    int w = img.getWidth();
                    int h = img.getHeight();

                    if (w < 100 || h < 100) continue;

                    BufferedImage bi;
                    try {
                        bi = img.getImage();
                    } catch (Exception e) {
                        logger.warn("Skipping problematic image: {}", name.getName(), e);
                        continue;
                    }

                    if (w > targetMaxDimension || h > targetMaxDimension) {
                        double scale = (double) targetMaxDimension / Math.max(w, h);
                        int newW = (int) (w * scale);
                        int newH = (int) (h * scale);

                        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g2d = scaled.createGraphics();
                        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        g2d.drawImage(bi, 0, 0, newW, newH, null);
                        g2d.dispose();

                        bi = scaled;
                        anyChange = true;
                    }

                    try {
                        PDImageXObject compressed = JPEGFactory.createFromImage(doc, bi, jpegQuality);
                        res.put(name, compressed);
                        anyChange = true;
                    } catch (Exception e) {
                        logger.warn("Failed to JPEG-compress image {} ({}×{}), keeping original", name.getName(), w, h, e);
                    }
                }
            }

            doc.setVersion(Math.max(doc.getVersion(), 1.5f));

            doc.save(tempOut.toString());

            byte[] result = Files.readAllBytes(tempOut);
            Files.deleteIfExists(tempOut);

            long newSize = result.length;
            double reduction = originalSize > 0 ? (1.0 - (double) newSize / originalSize) * 100 : 0;

            logger.info("Compression result: {} bytes → {} bytes ({:.1f}% reduction) | images processed: {}",
                    originalSize, newSize, reduction, anyChange ? "yes" : "no (or none found)");

            return result;

        } catch (Exception e) {
            Files.deleteIfExists(tempOut);
            logger.error("Compression failed", e);
            throw new IOException("PDF compression failed", e);
        }
    }

    private void cleanupUploadedFiles(List<Path> paths) {
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
                logger.debug("Deleted uploaded temp file: {}", path);
            } catch (IOException e) {
                logger.warn("Failed to delete uploaded temp file: {}", path, e);
            }
        }
    }
}