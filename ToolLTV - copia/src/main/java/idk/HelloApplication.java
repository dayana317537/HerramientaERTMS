package idk;
//import javafx.application.Application;
//import javafx.fxml.FXMLLoader;
//import javafx.scene.Scene;
//import javafx.stage.Stage;
//
//import java.io.IOException;
//
//public class HelloApplication extends Application {
//    @Override
//    public void start(Stage stage) throws IOException {
//        FXMLLoader fxmlLoader = new FXMLLoader(HelloApplication.class.getResource("hello-view.fxml"));
//        Scene scene = new Scene(fxmlLoader.load(), 320, 240);
//        stage.setTitle("Calculador LTV");
//        stage.setScene(scene);
//        stage.show();
//    }
//}

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class HelloApplication extends Application {

    private static Stage primaryStage;

    @Override
    public void start(Stage stage) throws IOException {
        primaryStage = stage;
        showMainView();
    }

    public static void showMainView() throws IOException {
        FXMLLoader loader = new FXMLLoader(
                HelloApplication.class.getResource("main-view.fxml"));
        Scene scene = new Scene(loader.load(), 700, 500);
        scene.getStylesheets().add(
                HelloApplication.class.getResource("styles.css").toExternalForm());
        primaryStage.setTitle("Calculador LTV — ETCS");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void showResultsView(LtvService.CalcResult result) throws IOException {
        FXMLLoader loader = new FXMLLoader(
                HelloApplication.class.getResource("results-view.fxml"));
        Scene scene = new Scene(loader.load(), 900, 600);
        scene.getStylesheets().add(
                HelloApplication.class.getResource("styles.css").toExternalForm());

        ResultsController controller = loader.getController();
        controller.setResult(result);

        primaryStage.setTitle("Resultados LTV — ETCS");
        primaryStage.setScene(scene);
    }
}
