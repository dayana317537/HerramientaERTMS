package idk;

import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.util.List;

public class HelloController {

    @FXML private TextField pkField;
    @FXML private TextField speedField;
    @FXML private TextField lengthField;
    @FXML private ComboBox<LtvService.SegmentOption> segmentCombo;
    @FXML private Label statusLabel;
    @FXML private Button buscarBtn;
    @FXML private Button calcularBtn;

    private final LtvService service = new LtvService();

    @FXML
    private void onBuscarSegmentos() {
        try {
            double pk     = Double.parseDouble(pkField.getText().trim());
            double speed  = Double.parseDouble(speedField.getText().trim());
            double length = Double.parseDouble(lengthField.getText().trim());

            List<LtvService.SegmentOption> options = service.buscarCandidatos(pk, speed, length);
            segmentCombo.setItems(FXCollections.observableArrayList(options));

            if (!options.isEmpty()) {
                segmentCombo.getSelectionModel().selectFirst();
                setStatus("Se encontraron " + options.size() + " segmento(s) candidato(s).", false);
                calcularBtn.setDisable(false);
            } else {
                setStatus("No se encontraron segmentos para los parámetros introducidos.", true);
                calcularBtn.setDisable(true);
            }

        } catch (NumberFormatException e) {
            setStatus("Error: comprueba que los valores numéricos son correctos.", true);
        } catch (Exception e) {
            setStatus("Error al buscar segmentos: " + e.getMessage(), true);
            e.printStackTrace();
        }
    }

    @FXML
    private void onCalcular() {
        LtvService.SegmentOption selected = segmentCombo.getValue();
        if (selected == null) {
            setStatus("Primero busca y selecciona un segmento.", true);
            return;
        }

        try {
            double pk     = Double.parseDouble(pkField.getText().trim());
            double speed  = Double.parseDouble(speedField.getText().trim());
            double length = Double.parseDouble(lengthField.getText().trim());

            setStatus("Calculando...", false);
            buscarBtn.setDisable(true);
            calcularBtn.setDisable(true);

            // Ejecutar en hilo secundario para no bloquear la UI
            Task<LtvService.CalcResult> task = new Task<>() {
                @Override
                protected LtvService.CalcResult call() throws Exception {
                    return service.calcularCompleto(pk, speed, length, selected.getId());
                }
            };

            task.setOnSucceeded(e -> {
                buscarBtn.setDisable(false);
                calcularBtn.setDisable(false);
                try {
                    HelloApplication.showResultsView(task.getValue());
                } catch (Exception ex) {
                    setStatus("Error al mostrar resultados: " + ex.getMessage(), true);
                    ex.printStackTrace();
                }
            });

            task.setOnFailed(e -> {
                buscarBtn.setDisable(false);
                calcularBtn.setDisable(false);
                setStatus("Error en el cálculo: " + task.getException().getMessage(), true);
                task.getException().printStackTrace();
            });

            new Thread(task).start();

        } catch (NumberFormatException e) {
            setStatus("Error: comprueba que los valores numéricos son correctos.", true);
        }
    }

    private void setStatus(String message, boolean isError) {
        statusLabel.setText(message);
        statusLabel.getStyleClass().removeAll("status-error", "status-ok");
        statusLabel.getStyleClass().add(isError ? "status-error" : "status-ok");
    }
}
