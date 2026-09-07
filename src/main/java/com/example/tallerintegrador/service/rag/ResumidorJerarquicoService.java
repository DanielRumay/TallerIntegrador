package com.example.tallerintegrador.service.rag;
import com.example.tallerintegrador.service.ia.GeminiService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Resumen jerarquico del documento, al estilo RAPTOR.
 *
 * REFERENCIA: Sarthi, P. et al. (2024). "RAPTOR: Recursive Abstractive Processing for
 * Tree-Organized Retrieval". ICLR 2024 (arXiv:2401.18059).
 *
 * EL PROBLEMA QUE RESUELVE. La busqueda por similitud recupera fragmentos PARECIDOS a la
 * consulta. Sobre una obra de 600 paginas eso significa 8 fragmentos de 2000 caracteres: el
 * 1,6 % del libro, y siempre de detalle literal. Una pregunta como "como cambia el personaje
 * a lo largo de la obra" no tiene respuesta posible con ese material, porque la respuesta no
 * esta en ningun fragmento: esta repartida entre doscientas paginas.
 *
 * LA SOLUCION. Se generan resumenes en dos niveles por encima del texto literal y se guardan
 * como vectores mas, en la misma coleccion:
 *
 *   nivel 0 -> fragmento literal de 2000 caracteres   (que dice el texto)
 *   nivel 1 -> resumen de una seccion o capitulo      (que ocurre en esta parte)
 *   nivel 2 -> resumen de la obra completa            (de que trata todo)
 *
 * ESTRATEGIA DE CONSULTA: "arbol colapsado" (collapsed tree). Todos los niveles viven en el
 * mismo indice y se buscan a la vez, en vez de recorrer el arbol de arriba abajo. El propio
 * articulo mide las dos variantes y adopta la colapsada para sus resultados principales por
 * ser mas flexible y rendir mejor. Para nosotros tiene ademas una ventaja practica decisiva:
 * NO hay que tocar el recuperador. Los resumenes son vectores normales con un metadato
 * `nivel`, asi que RagRetrieverService los encuentra sin cambiar una linea.
 *
 * EL NIVEL 2 SE CONSTRUYE POR FUSION JERARQUICA, no mandando el libro entero al modelo: se
 * resumen las secciones, y si hay demasiadas se agrupan los resumenes y se vuelven a resumir
 * hasta que caben en una sola llamada. Es el patron de "hierarchical merging" de la
 * literatura de resumen de documentos largos.
 *
 * NADA DE ESTO ES OBLIGATORIO PARA QUE LA INGESTA FUNCIONE. Si el modelo falla, se devuelven
 * listas vacias y el documento queda indexado igual con sus fragmentos de nivel 0. Un resumen
 * es una mejora de la recuperacion, no un requisito para guardar el material del docente.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumidorJerarquicoService {

    private final GeminiService geminiService;

    /** Caracteres de la seccion que se envian al modelo para resumirla. */
    private static final int MUESTRA_SECCION = 12_000;

    /** Resumenes de seccion que se juntan en cada paso de fusion del nivel 2. */
    private static final int GRUPO_FUSION = 8;

    /** Tope de pasadas de fusion, por si el documento fuera absurdamente grande. */
    private static final int MAX_PASADAS_FUSION = 4;

    public record ResumenSeccion(String titulo, String resumen, int orden) {}

    /**
     * Junta secciones contiguas hasta caber en MAX_RESUMENES_NIVEL_1.
     *
     * El titulo del grupo es el de la primera seccion que lo abre: es el que un alumno
     * reconoceria ("Capitulo 4" y no "Capitulo 4-6"), y el contenido de las siguientes sigue
     * dentro del texto que se resume.
     */
    private List<SegmentadorDocumentoService.Seccion> agruparSiSonDemasiadas(
            List<SegmentadorDocumentoService.Seccion> secciones) {

        if (secciones.size() <= MAX_RESUMENES_NIVEL_1) return secciones;

        int porGrupo = (int) Math.ceil(secciones.size() / (double) MAX_RESUMENES_NIVEL_1);
        List<SegmentadorDocumentoService.Seccion> agrupadas = new ArrayList<>();

        for (int i = 0; i < secciones.size(); i += porGrupo) {
            List<SegmentadorDocumentoService.Seccion> grupo =
                    secciones.subList(i, Math.min(i + porGrupo, secciones.size()));

            StringBuilder texto = new StringBuilder();
            for (var s : grupo) {
                if (texto.length() > 0) texto.append("\n\n");
                if (s.titulo() != null) texto.append(s.titulo()).append("\n");
                texto.append(s.texto());
            }

            agrupadas.add(new SegmentadorDocumentoService.Seccion(
                    grupo.get(0).titulo(), texto.toString(), agrupadas.size()));
        }
        return agrupadas;
    }

    /**
     * Techo de llamadas al modelo para el nivel 1.
     *
     * La primera version no tenia ninguno: recorria todas las secciones. Un libro sin
     * capitulos detectables se parte en unos 83 bloques, asi que eran ~83 llamadas solo para
     * el nivel 1, mas las de fusion. Cerca de cien peticiones por documento.
     */
    static final int MAX_RESUMENES_NIVEL_1 = 30;

    /**
     * Nivel 1: un resumen por seccion, sin pasar del techo de llamadas.
     *
     * NO se muestrea, se AGRUPA. En los subtemas si se puede saltar secciones, porque basta
     * recoger conceptos representativos; aqui no, porque un capitulo sin resumir es un trozo
     * de la obra que queda invisible para cualquier pregunta de comprension global.
     *
     * Por eso, cuando hay demasiadas secciones se juntan las CONTIGUAS: con 83 bloques y un
     * techo de 30, cada resumen cubre 3 bloques seguidos. Se conserva la cobertura completa a
     * cambio de menos detalle por resumen, que es el intercambio correcto — y es ademas lo
     * que hace de forma natural la fusion un nivel mas arriba.
     */
    public List<ResumenSeccion> resumirSecciones(List<SegmentadorDocumentoService.Seccion> secciones) {
        return resumirSecciones(secciones, null);
    }

    /**
     * Igual que el anterior, pero avisando del avance tras cada seccion resumida.
     *
     * El aviso se recibe como una funcion y NO como una dependencia al servicio de progreso:
     * este resumidor no tiene por que saber que existe una barra en una pantalla. Ademas asi
     * las pruebas lo siguen construyendo con un solo argumento.
     *
     * Importa que el avance salga de AQUI y no del que llama: cada seccion es una llamada a
     * Gemini de varios segundos, y quien invoca solo recupera el control cuando ya estan
     * todas hechas. Por eso el contador se quedaba clavado en "0 de 12" hasta el final.
     *
     * @param avance recibe (secciones resumidas, total a resumir). Puede ser null.
     */
    public List<ResumenSeccion> resumirSecciones(
            List<SegmentadorDocumentoService.Seccion> secciones,
            java.util.function.BiConsumer<Integer, Integer> avance) {

        List<ResumenSeccion> resumenes = new ArrayList<>();
        if (secciones == null || secciones.isEmpty()) return resumenes;

        int seccionesOriginales = secciones.size();
        secciones = agruparSiSonDemasiadas(secciones);
        if (secciones.size() < seccionesOriginales) {
            log.info("[RESUMEN] {} secciones agrupadas en {} para no pasar de {} llamadas",
                    seccionesOriginales, secciones.size(), MAX_RESUMENES_NIVEL_1);
        }

        // El total que se anuncia es el de secciones YA agrupadas: es el numero real de
        // pasos que veran avanzar. Anunciar el original haria que la cuenta se detuviera
        // antes de llegar al final sin que nada fallara.
        int total = secciones.size();
        int hechas = 0;
        avisar(avance, hechas, total);

        for (var seccion : secciones) {
            String muestra = seccion.texto().length() > MUESTRA_SECCION
                    ? seccion.texto().substring(0, MUESTRA_SECCION)
                    : seccion.texto();

            String prompt = """
                    Resume esta seccion de un documento educativo en 4 a 6 oraciones.

                    Explica QUE se trata y QUE ideas principales aparecen, no como esta escrito.
                    Si es un texto narrativo, di que ocurre y que cambia. Si es expositivo, di
                    que conceptos se explican y como se relacionan entre si.

                    Escribe solo el resumen, sin titulos, sin vinetas y sin introducciones.
                    """
                    + (seccion.titulo() != null ? "\n\nTITULO: " + seccion.titulo() : "")
                    + "\n\nTEXTO:\n" + muestra;

            try {
                String resumen = geminiService.askGemini(prompt).text();
                if (resumen != null && !resumen.isBlank()) {
                    resumenes.add(new ResumenSeccion(seccion.titulo(), resumen.strip(), seccion.orden()));
                }
            } catch (Exception e) {
                // Una seccion sin resumen no invalida las demas ni la ingesta.
                log.warn("[RESUMEN] Seccion {} sin resumen: {}", seccion.orden(), e.getMessage());
            }

            // Se cuenta la seccion PROCESADA, no la resumida con exito: si una falla, el
            // contador debe seguir avanzando o la barra se quedaria corta para siempre.
            hechas++;
            avisar(avance, hechas, total);
        }

        log.info("[RESUMEN] Nivel 1: {} resumenes de seccion sobre {} secciones",
                resumenes.size(), secciones.size());
        return resumenes;
    }

    /** Un fallo al notificar el avance no puede tumbar la ingesta: es cosmetico. */
    private void avisar(java.util.function.BiConsumer<Integer, Integer> avance, int hechas, int total) {
        if (avance == null) return;
        try {
            avance.accept(hechas, total);
        } catch (Exception e) {
            log.debug("[RESUMEN] No se pudo notificar el avance: {}", e.getMessage());
        }
    }

    /**
     * Nivel 2: resumen de la obra completa, por fusion jerarquica de los de nivel 1.
     *
     * @return el resumen global, o null si no se pudo construir.
     */
    public String resumirDocumento(List<ResumenSeccion> resumenesSeccion) {
        if (resumenesSeccion == null || resumenesSeccion.isEmpty()) return null;

        // Con una sola seccion, su resumen YA es el del documento. Volver a resumirlo solo
        // gastaria una llamada para perder informacion.
        if (resumenesSeccion.size() == 1) {
            return resumenesSeccion.get(0).resumen();
        }

        List<String> nivel = resumenesSeccion.stream().map(ResumenSeccion::resumen).toList();

        for (int pasada = 0; pasada < MAX_PASADAS_FUSION && nivel.size() > 1; pasada++) {
            List<String> siguiente = new ArrayList<>();

            for (int i = 0; i < nivel.size(); i += GRUPO_FUSION) {
                List<String> grupo = nivel.subList(i, Math.min(i + GRUPO_FUSION, nivel.size()));

                // Un grupo de uno pasa tal cual: fusionarlo consigo mismo no aporta nada.
                if (grupo.size() == 1) {
                    siguiente.add(grupo.get(0));
                    continue;
                }

                String fusionado = fusionar(grupo, nivel.size() <= GRUPO_FUSION);
                siguiente.add(fusionado != null ? fusionado : String.join("\n\n", grupo));
            }

            log.info("[RESUMEN] Fusion pasada {}: {} -> {}", pasada + 1, nivel.size(), siguiente.size());
            nivel = siguiente;
        }

        return nivel.isEmpty() ? null : nivel.get(0);
    }

    /**
     * @param esUltimaPasada cuando ya se esta produciendo el resumen final, se pide una vision
     *                       de conjunto; en pasadas intermedias se pide conservar informacion,
     *                       porque resumir de mas en un paso intermedio pierde datos que las
     *                       pasadas siguientes ya no pueden recuperar.
     */
    private String fusionar(List<String> resumenes, boolean esUltimaPasada) {
        String instruccion = esUltimaPasada
                ? "Integra estos resumenes parciales en un unico resumen general de la obra, de 8 a 12 "
                  + "oraciones. Describe de que trata en conjunto, que ideas o hilos la recorren de "
                  + "principio a fin, y como evoluciona."
                : "Integra estos resumenes parciales en uno solo, CONSERVANDO la informacion concreta "
                  + "de cada uno. No generalices todavia: este resumen se combinara despues con otros.";

        String prompt = instruccion
                + "\n\nEscribe solo el resultado, sin titulos ni vinetas.\n\nRESUMENES:\n"
                + String.join("\n\n---\n\n", resumenes);

        try {
            String r = geminiService.askGemini(prompt).text();
            return (r == null || r.isBlank()) ? null : r.strip();
        } catch (Exception e) {
            log.warn("[RESUMEN] Fusion fallida: {}", e.getMessage());
            return null;
        }
    }
}
