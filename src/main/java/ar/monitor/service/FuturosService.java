package ar.monitor.service;

import ar.monitor.model.FuturoDto;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class FuturosService {

    // Paso 1: valores provistos (estático); luego reemplazamos con fuente real.
    public List<FuturoDto> fetchFuturos() {
        return Arrays.asList(
            new FuturoDto("AGO25","1.312,000","53,63","70,31"),
            new FuturoDto("SEP25","1.373,000","53,77","68,49"),
            new FuturoDto("OCT25","1.432,000","53,75","66,62"),
            new FuturoDto("NOV25","1.464,000","47,79","56,65"),
            new FuturoDto("DIC25","1.499,000","43,72","50,04"),
            new FuturoDto("ENE26","1.530,000","40,79","45,44"),
            new FuturoDto("FEB26","1.555,000","38,52","42,01"),
            new FuturoDto("MAR26","1.586,000","36,92","39,49"),
            new FuturoDto("ABR26","1.607,000","34,90","36,67"),
            new FuturoDto("MAY26","1.636,000","34,21","35,45"),
            new FuturoDto("JUN26","1.665,000","33,34","34,04"),
            new FuturoDto("JUL26","1.690,000","32,39","32,64")
        );
    }
}