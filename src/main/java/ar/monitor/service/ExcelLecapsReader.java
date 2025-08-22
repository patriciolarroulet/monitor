package ar.monitor.service;

import ar.monitor.model.InstrumentoDto;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.*;

/**
 * LEE SOLO LAS COLUMNAS A..G del Excel:
 *   A: ticker / especie / símbolo
 *   B: isin
 *   C: emisor
 *   D: vencimiento
 *   E: capital (VN)
 *   F: interes (cupón)
 *   G: valor_final (VF)
 *
 * NO lee ni usa: precio, fecha, hora, TEM/TNA/TEA, fecha_liquidacion,
 * días al vto, comisiones, etc. Esos datos quedan para el push de Python.
 */
@Service
public class ExcelLecapsReader {

    private final ResourceLoader resourceLoader;

    public ExcelLecapsReader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    // ------------------- normalización encabezados -------------------
    private static String norm(String s) {
        if (s == null) return null;
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return n.trim().toLowerCase().replaceAll("[\\s]+", " ").replace(' ', '_');
    }

    // ------------------- alias encabezados -> clave interna -------------------
    private static final Map<String, String> HEADER_ALIAS = new HashMap<>();
    static {
        // A: Ticker
        HEADER_ALIAS.put("ticker","ticker");
        HEADER_ALIAS.put("especie","ticker");
        HEADER_ALIAS.put("simbolo","ticker");
        HEADER_ALIAS.put("símbolo","ticker");

        // B: ISIN
        HEADER_ALIAS.put("isin","isin");

        // C: Emisor
        HEADER_ALIAS.put("emisor","emisor");

        // D: Vencimiento (solo texto/fecha tal como venga)
        HEADER_ALIAS.put("vencimiento","vencimiento");
        HEADER_ALIAS.put("fecha_vto","vencimiento");
        HEADER_ALIAS.put("fecha_de_vencimiento","vencimiento");
        HEADER_ALIAS.put("maturity","vencimiento");

        // E: Capital (VN)
        HEADER_ALIAS.put("capital","capital");
        HEADER_ALIAS.put("valor_nominal","capital");
        HEADER_ALIAS.put("vn","capital");

        // F: Interés (cupón)
        HEADER_ALIAS.put("interes","interes");
        HEADER_ALIAS.put("interés","interes");
        HEADER_ALIAS.put("cupon","interes");
        HEADER_ALIAS.put("cupón","interes");

        // G: Valor Final (VF)
        HEADER_ALIAS.put("valor_final","valor_final");
        HEADER_ALIAS.put("valor_al_vencimiento","valor_final");
        HEADER_ALIAS.put("redemption_value","valor_final");
        HEADER_ALIAS.put("vf","valor_final");
    }

    // ------------------- API -------------------
    public List<InstrumentoDto> load(String path, String sheetName) {
        try {
            Resource res = resourceLoader.getResource(path);
            try (InputStream is = res.getInputStream(); Workbook wb = new XSSFWorkbook(is)) {

                Sheet sheet = wb.getSheet(sheetName);
                if (sheet == null) throw new RuntimeException("No se encontró la hoja: " + sheetName);

                Row header = sheet.getRow(0);
                if (header == null) throw new RuntimeException("No hay encabezados (fila 1)");

                // Mapear encabezados -> índice de columna
                Map<String,Integer> idxByNorm = new HashMap<>();
                for (int c = 0; c < header.getLastCellNum(); c++) {
                    Cell cell = header.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell == null) continue;
                    if (cell.getCellType() != CellType.STRING) continue;
                    idxByNorm.put(norm(cell.getStringCellValue()), c);
                }

                Map<String,Integer> col = new HashMap<>();
                for (Map.Entry<String,Integer> e : idxByNorm.entrySet()) {
                    String alias = HEADER_ALIAS.get(e.getKey());
                    if (alias != null && !col.containsKey(alias)) col.put(alias, e.getValue());
                }

                // Validaciones mínimas: necesitamos al menos ticker y valor_final
                if (!col.containsKey("ticker"))
                    throw new RuntimeException("No se encontró la columna 'ticker' (A).");
                if (!col.containsKey("valor_final"))
                    throw new RuntimeException("No se encontró la columna 'valor_final' (G).");

                List<InstrumentoDto> out = new ArrayList<>();

                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;

                    String ticker = getString(row, col.get("ticker"));
                    if (ticker == null || ticker.isBlank()) continue; // fila vacía

                    InstrumentoDto dto = new InstrumentoDto();
                    dto.setTicker(ticker);
                    dto.setIsin(getString(row, col.get("isin")));
                    dto.setEmisor(getString(row, col.get("emisor")));
                    dto.setVencimiento(getString(row, col.get("vencimiento"))); // sin parsear: lo tratás luego

                    dto.setCapital(getDouble(row, col.get("capital")));
                    dto.setInteres(getDouble(row, col.get("interes")));
                    dto.setValorFinal(getDouble(row, col.get("valor_final")));

                    // IMPORTANTÍSIMO: NO setear nada más. Todo lo demás viene del push de Python
                    // dto.setPrecio(null);
                    // dto.setFecha(null);
                    // dto.setHoraInput(null);
                    // dto.setFechaLiquidacion(null);
                    // dto.setDiasAlVto(null);
                    // dto.setTemBruta(null); dto.setTnaSimpleBruta(null); dto.setTeaBruta(null);
                    // dto.setTemNeta(null);  dto.setTnaSimpleNeta(null);  dto.setTeaNeta(null);

                    out.add(dto);
                }
                return out;
            }
        } catch (Exception e) {
            throw new RuntimeException("Error leyendo Excel (A..G únicamente): " + e.getMessage(), e);
        }
    }

    // ------------------- helpers mínimos -------------------
    private static String getString(Row row, Integer col) {
        if (col == null || col < 0) return null;
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;

        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue();

        if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                // lo devolvemos como texto ISO simple; si después querés otro formato, lo transformás en el servicio de Python
                return cell.getLocalDateTimeCellValue().toLocalDate().toString();
            }
            double v = cell.getNumericCellValue();
            long lv = (long) v;
            return (v == lv) ? String.valueOf(lv) : String.valueOf(v);
        }
        return null;
    }

    private static Double getDouble(Row row, Integer col) {
        if (col == null || col < 0) return null;
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;

        if (cell.getCellType() == CellType.NUMERIC) return cell.getNumericCellValue();
        if (cell.getCellType() == CellType.STRING) {
            try {
                String s = cell.getStringCellValue().trim();
                s = s.replace(",", "."); // por si viene coma decimal
                if (s.isEmpty()) return null;
                return Double.valueOf(s);
            } catch (Exception ignored) {}
        }
        return null;
    }
}
