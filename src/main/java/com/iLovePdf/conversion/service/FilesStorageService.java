package com.iLovePdf.conversion.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface FilesStorageService {

    String save(MultipartFile file);

    byte[] convertToWordBytes(String pdfPath) throws IOException;

    byte[] convertToMarkdownBytes(String pdfPath) throws IOException;

    byte[] convertToJsonBytes(String pdfPath) throws IOException;

    byte[] convertToCsvBytes(String pdfPath) throws IOException;

    byte[] convertToJpgBytes(String pdfPath) throws IOException;

    byte[] mergePdfsBytes(List<String> pdfPaths) throws IOException;
}