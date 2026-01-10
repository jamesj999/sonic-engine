package uk.co.jamesj999.sonic.level.objects;

public interface SlopedSolidProvider extends SolidObjectProvider {
    byte[] getSlopeData();

    boolean isSlopeFlipped();
}
