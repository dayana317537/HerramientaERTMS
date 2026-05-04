package idk.toolltv;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

import java.util.List;

public class HelloController {

    @FXML
    private TextField pkField;

    @FXML
    private TextField speedField;

    @FXML
    private TextField lengthField;

    @FXML
    private ComboBox<LtvService.SegmentOption> segmentCombo;

    @FXML
    private TextArea resultArea;

    private final LtvService service = new LtvService();

    @FXML
    private void onBuscarSegmentos() {
        try {
            double pk = Double.parseDouble(pkField.getText());
            double speed = Double.parseDouble(speedField.getText());
            double length = Double.parseDouble(lengthField.getText());

            List<LtvService.SegmentOption> options = service.buscarCandidatos(pk, speed, length);

            segmentCombo.setItems(FXCollections.observableArrayList(options));

            if (!options.isEmpty()) {
                segmentCombo.getSelectionModel().selectFirst();
                resultArea.setText("Se han encontrado " + options.size() + " segmentos candidatos.");
            } else {
                resultArea.setText("No se encontraron segmentos candidatos.");
            }

        } catch (NumberFormatException e) {
            resultArea.setText("Error: revisa los valores numéricos.");
        } catch (Exception e) {
            resultArea.setText("Error al buscar segmentos:\n" + e.getMessage());
            e.printStackTrace();
        }
    }

    @FXML
    private void onCalcular() {
        try {
            double pk = Double.parseDouble(pkField.getText());
            double speed = Double.parseDouble(speedField.getText());
            double length = Double.parseDouble(lengthField.getText());

            LtvService.SegmentOption selected = segmentCombo.getValue();
            if (selected == null) {
                resultArea.setText("Primero busca y selecciona un segmento.");
                return;
            }

            String resultado = service.calcular(pk, speed, length, selected.getId());
            resultArea.setText(resultado);

        } catch (NumberFormatException e) {
            resultArea.setText("Error: revisa los valores numéricos.");
        } catch (Exception e) {
            resultArea.setText("Error al ejecutar el cálculo:\n" + e.getMessage());
            e.printStackTrace();
        }
    }
}