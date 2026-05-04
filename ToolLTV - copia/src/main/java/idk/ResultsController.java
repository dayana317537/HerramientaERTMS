package idk;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.PrintWriter;

public class ResultsController {

    @FXML private Label titleLabel;
    @FXML private TableView<LtvService.TelegramRow> table;
    @FXML private TableColumn<LtvService.TelegramRow, String>  colBaliza;
    @FXML private TableColumn<LtvService.TelegramRow, Double>  colDistancia;
    @FXML private TableColumn<LtvService.TelegramRow, Double>  colPk;
    @FXML private TableColumn<LtvService.TelegramRow, String>  colVia;
    @FXML private TableColumn<LtvService.TelegramRow, Boolean> colTipo;
    @FXML private TextArea detalleArea;

    private LtvService.CalcResult result;

    public void setResult(LtvService.CalcResult result) {
        this.result = result;

        // Título resumen
        titleLabel.setText(String.format(
                "LTV  PK=%.2f m  |  %.0f km/h  |  Longitud=%.1f m",
                result.pkLtv, result.ltvSpeedKmh, result.ltvLengthMeters));

        // Configurar columnas
        colBaliza.setCellValueFactory(new PropertyValueFactory<>("baliseName"));
        colDistancia.setCellValueFactory(new PropertyValueFactory<>("distanceMeters"));
        colPk.setCellValueFactory(new PropertyValueFactory<>("pk"));
        colVia.setCellValueFactory(new PropertyValueFactory<>("via"));

        // Columna tipo con texto legible
        colTipo.setCellValueFactory(new PropertyValueFactory<>("isSignal"));
        colTipo.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Boolean isSignal, boolean empty) {
                super.updateItem(isSignal, empty);
                if (empty || isSignal == null) {
                    setText(null);
                } else {
                    setText(isSignal ? "Señal" : "Frenado");
                    getStyleClass().removeAll("tag-signal", "tag-braking");
                    getStyleClass().add(isSignal ? "tag-signal" : "tag-braking");
                }
            }
        });

        // Formatear columnas numéricas
        colDistancia.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double val, boolean empty) {
                super.updateItem(val, empty);
                setText(empty || val == null ? null : String.format("%.2f m", val));
            }
        });
        colPk.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double val, boolean empty) {
                super.updateItem(val, empty);
                setText(empty || val == null ? null : String.format("%.2f m", val));
            }
        });

        // Rellenar tabla
        table.getItems().setAll(result.filasTelegramas);

        // Texto detallado
        detalleArea.setText(result.detalleTexto);
    }

    @FXML
    private void onDescargarTxt() {
        if (result == null) return;

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Guardar telegramas");
        chooser.setInitialFileName(
                String.format("telegramas_LTV_%.0f.txt", result.pkLtv));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Fichero de texto", "*.txt"));

        File file = chooser.showSaveDialog(table.getScene().getWindow());
        if (file == null) return;

        try (PrintWriter pw = new PrintWriter(file)) {
            pw.print(result.contenidoTxt);
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR,
                    "No se pudo guardar el fichero:\n" + e.getMessage()).showAndWait();
        }
    }

    @FXML
    private void onVolver() throws Exception {
        HelloApplication.showMainView();
    }
}
