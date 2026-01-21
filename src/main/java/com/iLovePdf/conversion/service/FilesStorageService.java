package com.iLovePdf.conversion.service;

import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.util.List;

public interface FilesStorageService {
    String save(MultipartFile file);
    File convertToWord(String pdfPath);
    File convertToMarkdown(String pdfPath);
    File convertToJson(String pdfPath);
    File convertToCsv(String pdfPath);
    File convertToJpg(String pdfPath);
    File mergePdfs(List<String> pdfPaths);
}
