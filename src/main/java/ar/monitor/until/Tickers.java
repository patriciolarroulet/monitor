package ar.monitor.until;

public final class Tickers {
  private Tickers(){}

  /** Normaliza: trim, mayúsculas, quita espacios, puntos y guiones. */
  public static String normalize(String t) {
    if (t == null) return null;
    String s = t.trim().toUpperCase();
    s = s.replace(" ", "");
    s = s.replace(".", "");
    s = s.replace("-", "");
    return s;
  }
}
