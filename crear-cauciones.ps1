# Usa la carpeta del script como raíz del proyecto, aunque lo ejecutes desde otro lado
$Root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }

# Rutas absolutas seguras
$dirModel   = Join-Path $Root "src\main\java\ar\monitor\model"
$dirService = Join-Path $Root "src\main\java\ar\monitor\service"
$dirWeb     = Join-Path $Root "src\main\java\ar\monitor\web"

# Crear carpetas
New-Item -ItemType Directory -Force -Path $dirModel   | Out-Null
New-Item -ItemType Directory -Force -Path $dirService | Out-Null
New-Item -ItemType Directory -Force -Path $dirWeb     | Out-Null

# Encoder UTF-8 SIN BOM
$enc = New-Object System.Text.UTF8Encoding($false)

# ========== 1) CaucionDto.java ==========
$path = Join-Path $dirModel "CaucionDto.java"
$content = @'
package ar.monitor.model;

import java.math.BigDecimal;

public class CaucionDto {
    private String plazo;    // ej: "7 días"
    private BigDecimal tna;  // ej: 41.50 (sin %)

    public CaucionDto() {}
    public CaucionDto(String plazo, BigDecimal tna) {
        this.plazo = plazo;
        this.tna = tna;
    }
    public String getPlazo() { return plazo; }
    public void setPlazo(String plazo) { this.plazo = plazo; }
    public BigDecimal getTna() { return tna; }
    public void setTna(BigDecimal tna) { this.tna = tna; }
}
'@
[System.IO.File]::WriteAllText($path, $content, $enc)

# ========== 2) CaucionesService.java ==========
$path = Join-Path $dirService "CaucionesService.java"
$content = @'
package ar.monitor.service;

import ar.monitor.model.CaucionDto;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CaucionesService {

    private static final String SOURCE_URL = "https://dalfie.ar/ccl/";

    private static final Pattern LINE_PATTERN = Pattern.compile(
            "(?<dias>\\b\\d{1,3})\\s*d[ií]as?\\b.*?(?<tna>\\d{1,3},\\d{1,2})\\s*%",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final List<Integer> TARGET_DAYS = Arrays.asList(1, 7, 14, 21, 28, 35, 42);

    public List<CaucionDto> fetchCauciones() throws IOException {
        Document doc = Jsoup.connect(SOURCE_URL)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Monitor/1.0")
                .referrer("https://www.google.com")
                .timeout(12000)
                .get();

        Map<Integer, BigDecimal> result = new HashMap<>();

        Elements tables = doc.select("table");
        for (Element t : tables) {
            String all = t.text().toLowerCase();
            if (all.contains("caucion") || all.contains("cauciones") || all.contains("tna")) {
                for (Element tr : t.select("tr")) parseRowToMap(tr.text(), result);
            }
        }

        if (result.isEmpty()) {
            Matcher m = LINE_PATTERN.matcher(doc.body().text());
            while (m.find()) putIfTarget(m, result);
        }

        List<CaucionDto> out = new ArrayList<>();
        for (Integer d : TARGET_DAYS) {
            BigDecimal tna = result.get(d);
            if (tna != null) out.add(new CaucionDto(d + " días", tna));
        }
        return out;
    }

    private void parseRowToMap(String row, Map<Integer, BigDecimal> map) {
        Matcher m = LINE_PATTERN.matcher(row);
        while (m.find()) putIfTarget(m, map);
    }

    private void putIfTarget(Matcher m, Map<Integer, BigDecimal> map) {
        int dias = Integer.parseInt(m.group("dias"));
        if (!TARGET_DAYS.contains(dias)) return;
        BigDecimal tna = parseArgBig(m.group("tna"));
        map.put(dias, tna);
    }

    private BigDecimal parseArgBig(String s) {
        DecimalFormatSymbols sym = new DecimalFormatSymbols();
        sym.setDecimalSeparator(',');
        DecimalFormat df = new DecimalFormat("#,##0.00", sym);
        try {
            return new BigDecimal(df.parse(s).toString());
        } catch (Exception ignored) {
            return new BigDecimal(s.replace(".", "").replace(',', '.'));
        }
    }
}
'@
[System.IO.File]::WriteAllText($path, $content, $enc)

# ========== 3) ExternalsController.java ==========
$path = Join-Path $dirWeb "ExternalsController.java"
$content = @'
package ar.monitor.web;

import ar.monitor.model.CaucionDto;
import ar.monitor.service.CaucionesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/externals")
@CrossOrigin
public class ExternalsController {

    private final CaucionesService caucionesService;

    public ExternalsController(CaucionesService caucionesService) {
        this.caucionesService = caucionesService;
    }

    @GetMapping("/cauciones")
    public ResponseEntity<List<CaucionDto>> cauciones() {
        try {
            List<CaucionDto> data = caucionesService.fetchCauciones();
            return ResponseEntity.ok(data);
        } catch (IOException e) {
            return ResponseEntity.status(502).body(Collections.emptyList());
        }
    }
}
'@
[System.IO.File]::WriteAllText($path, $content, $enc)

Write-Host "✅ Archivos creados en: $dirModel, $dirService, $dirWeb"
