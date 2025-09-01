package ar.monitor.service;

import org.apache.poi.ss.usermodel.*;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@Component
public class BusinessCalendar {

  private final ResourceLoader loader;
  private final Set<LocalDate> holidays = new HashSet<>();

  private static final String EXCEL_PATH = "classpath:data/API LECAPS_con_comisiones.xlsx";
  private static final String SHEET_FERIADOS = "FERIADOS";

  public BusinessCalendar(ResourceLoader loader) {
    this.loader = loader;
    loadHolidays();
  }

  private void loadHolidays() {
    try {
      Resource res = loader.getResource(EXCEL_PATH);
      try (InputStream in = res.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
        Sheet sh = wb.getSheet(SHEET_FERIADOS);
        if (sh == null) return;
        for (int r = sh.getFirstRowNum() + 1; r <= sh.getLastRowNum(); r++) {
          Row row = sh.getRow(r);
          if (row == null) continue;
          for (Cell c : row) {
            LocalDate d = asLocalDate(c);
            if (d != null) holidays.add(d);
          }
        }
      }
    } catch (Exception ignore) {}
  }

  private static LocalDate asLocalDate(Cell c){
    if (c == null) return null;
    if (c.getCellType()==CellType.NUMERIC && DateUtil.isCellDateFormatted(c))
      return c.getLocalDateTimeCellValue().toLocalDate();
    try {
      String s = c.getStringCellValue();
      if (s==null || s.isBlank()) return null;
      if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(s);
      if (s.matches("\\d{2}/\\d{2}/\\d{4}")) {
        String[] p = s.split("/");
        return LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]));
      }
    } catch(Exception ignore){}
    return null;
  }

  public boolean isBusinessDay(LocalDate d){
    DayOfWeek w = d.getDayOfWeek();
    if (w==DayOfWeek.SATURDAY || w==DayOfWeek.SUNDAY) return false;
    return !holidays.contains(d);
  }

  public LocalDate previousBusinessDay(LocalDate date){
    LocalDate d = date.minusDays(1);
    while (!isBusinessDay(d)) d = d.minusDays(1);
    return d;
  }
}
