package com.openggf.game.sonic3k.resources;

import com.openggf.game.timing.HardwareWorkPreparation;
import com.openggf.game.timing.HardwareWorkPreparationSnapshot;
import com.openggf.game.timing.HardwareWorkHandle;

import java.util.Objects;

/** Complete rewind state for one queued S3K KosM archive. */
public record S3kKosModuleSnapshot(
        S3kKosModuleDescriptor descriptor,
        byte[] archive,
        int completedModules,
        int activeModuleOffset,
        HardwareWorkHandle activeChild,
        int activeChildCompressedLength,
        byte[] output,
        boolean prepared) implements HardwareWorkPreparationSnapshot {

    public S3kKosModuleSnapshot {
        Objects.requireNonNull(descriptor, "descriptor");
        archive = Objects.requireNonNull(archive, "archive").clone();
        output = Objects.requireNonNull(output, "output").clone();
    }

    @Override
    public byte[] archive() {
        return archive.clone();
    }

    @Override
    public byte[] output() {
        return output.clone();
    }

    @Override
    public HardwareWorkPreparation recreatePreparation() {
        return S3kKosModuleQueue.recreatePreparation(this);
    }
}
