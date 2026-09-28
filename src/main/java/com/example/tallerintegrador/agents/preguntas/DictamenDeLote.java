package com.example.tallerintegrador.agents.preguntas;

import java.util.List;

/**
 * Respuesta de un critico cuando revisa un lote completo de reactivos en una sola llamada.
 *
 * POR QUE EN LOTE. Revisar reactivo por reactivo cuesta tres llamadas por pregunta: con diez
 * preguntas son treinta, que es lo que hacia inviable conectar el comite sin romper el limite
 * de latencia de la primera pregunta ni la cuota de IA de un aula. En lote son tres llamadas
 * en total, y como los tres criticos corren en paralelo, el costo en tiempo es el de una.
 *
 * EL RIESGO Y SU FRENO. Un revisor al que se le dan diez items tiende a repetir el mismo
 * veredicto por inercia — el mismo sesgo que justifica tener tres criticos separados en vez
 * de uno generalista. Por eso cada veredicto viene con su `id` y su `evidencia` propia: no se
 * puede juzgar en bloque si hay que citar algo distinto para cada reactivo.
 */
public record DictamenDeLote(List<VeredictoDeReactivo> veredictos) {}
