package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.model.Fracture;
import com.stratigraphic.mapping.model.StratumInterface;
import com.stratigraphic.mapping.model.StratumModel;
import javafx.application.Platform;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterPaneDielectricTest {

    @Test
    void dielectricConstantFieldsCanBeEditedAndReadBack() throws Exception {

        startToolkit();

        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                ParameterPane pane = new ParameterPane();
                StratumModel model = new StratumModel(
                        0.1, 100, 2.2, 90,
                        List.of(new StratumInterface(1.0)),
                        List.of(20.0, 30.0),
                        List.of(3.3, 4.4),
                        List.of(new Fracture(1.0, 30, 45, 100, 5.5)),
                        List.of(), List.of(), List.of());

                pane.setModel(model, "dielectric test");

                textField(pane, "wellDielectricField").setText("6.6");
                textFields(pane, "dielectricFields").get(0).setText("7.7");
                textField(pane, "fractureRows", 0, "dielectricField").setText("8.8");

                StratumModel edited = pane.toModel();
                assertEquals(6.6, edited.dielectricConstant(), 1e-12);
                assertEquals(7.7, edited.layerDielectricConstants().get(0), 1e-12);
                assertEquals(8.8, edited.fractures().get(0).dielectricConstant(), 1e-12);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                checked.countDown();
            }
        });

        assertTrue(checked.await(10, TimeUnit.SECONDS), "介电常数输入框检查超时");
        if (failure.get() != null) {
            throw new AssertionError("介电常数输入框编辑失败", failure.get());
        }
    }

    private static TextField textField(Object owner, String fieldName) throws Exception {
        return (TextField) readField(owner, fieldName);
    }

    private static TextField textField(Object owner, String listFieldName,
                                       int index, String fieldName) throws Exception {
        Object row = rows(owner, listFieldName).get(index);
        return (TextField) readField(row, fieldName);
    }

    @SuppressWarnings("unchecked")
    private static List<TextField> textFields(Object owner, String fieldName) throws Exception {
        return (List<TextField>) readField(owner, fieldName);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rows(Object owner, String fieldName) throws Exception {
        return (List<Object>) readField(owner, fieldName);
    }

    private static Object readField(Object owner, String fieldName) throws Exception {
        Field field = owner.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void startToolkit() throws InterruptedException {

        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            return;
        }
        assertTrue(started.await(10, TimeUnit.SECONDS), "JavaFX 工具包启动超时");
    }
}