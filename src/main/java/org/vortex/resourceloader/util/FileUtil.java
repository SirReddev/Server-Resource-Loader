package org.vortex.resourceloader.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.zip.ZipFile;

public class FileUtil {
    private static final String[] UNITS = new String[]{"B", "KB", "MB", "GB"};
    private static final DecimalFormat FORMAT = new DecimalFormat("#,##0.#");

    public static byte[] calcSHA1(File file) {
        if (!file.exists() || !file.isFile()) {
            return null;
        }
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    sha1.update(buffer, 0, read);
                }
            }
            return sha1.digest();
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return null;
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) return null;
        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    public static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        if (digitGroups >= UNITS.length) digitGroups = UNITS.length - 1;
        return FORMAT.format(bytes / Math.pow(1024, digitGroups)) + " " + UNITS[digitGroups];
    }

    public static void validateZipFile(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.size() == 0) {
                throw new IOException("Zip file is empty");
            }
        }
    }
}
