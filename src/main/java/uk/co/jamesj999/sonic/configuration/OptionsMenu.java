package uk.co.jamesj999.sonic.configuration;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class OptionsMenu extends JDialog {
    private final SonicConfigurationService configService = SonicConfigurationService.getInstance();
    private final Map<SonicConfiguration, JComponent> inputFields = new HashMap<>();

    public OptionsMenu(Frame owner) {
        super(owner, "Options", true);
        setLayout(new BorderLayout());

        JPanel fieldsPanel = new JPanel(new GridLayout(0, 2));
        JScrollPane scrollPane = new JScrollPane(fieldsPanel);
        add(scrollPane, BorderLayout.CENTER);

        for (SonicConfiguration config : SonicConfiguration.values()) {
            fieldsPanel.add(new JLabel(config.name()));
            Object value = configService.getConfigValue(config);
            JComponent input;

            if (value instanceof Boolean) {
                JCheckBox checkBox = new JCheckBox();
                checkBox.setSelected((Boolean) value);
                input = checkBox;
            } else {
                JTextField textField = new JTextField(value != null ? value.toString() : "");
                input = textField;
            }
            inputFields.put(config, input);
            fieldsPanel.add(input);
        }

        JPanel buttonPanel = new JPanel();
        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");

        okButton.addActionListener(e -> saveAndClose());
        cancelButton.addActionListener(e -> dispose());

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        add(buttonPanel, BorderLayout.SOUTH);

        setSize(400, 500);
        setLocationRelativeTo(owner);
    }

    private void saveAndClose() {
        for (Map.Entry<SonicConfiguration, JComponent> entry : inputFields.entrySet()) {
            SonicConfiguration key = entry.getKey();
            JComponent input = entry.getValue();
            Object currentValue = configService.getConfigValue(key);

            Object newValue = null;

            if (input instanceof JCheckBox) {
                newValue = ((JCheckBox) input).isSelected();
            } else if (input instanceof JTextField) {
                String text = ((JTextField) input).getText();
                if (currentValue instanceof Integer) {
                    try {
                        newValue = Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        newValue = currentValue;
                        System.err.println("Invalid integer for " + key + ": " + text);
                    }
                } else if (currentValue instanceof Double) {
                     try {
                        newValue = Double.parseDouble(text);
                    } catch (NumberFormatException e) {
                        newValue = currentValue;
                        System.err.println("Invalid double for " + key + ": " + text);
                    }
                } else {
                    newValue = text;
                }
            }

            if (newValue != null) {
                configService.setConfigValue(key, newValue);
            }
        }
        configService.saveConfig();
        dispose();
    }
}
