package com.openggf.editor.persistence;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
interface EditorSaveReader {
    EditorSaveEnvelope read(Path file) throws IOException;
}
