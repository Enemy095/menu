package com.salamu.menu.asset.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.salamu.menu.asset.api.AssetKind;

class AssetObjectWriterTests {

    @Test
    void validatesBeforePassingCanonicalObjectToStorage() {
        AssetObjectStorage storage = mock(AssetObjectStorage.class);
        AssetObjectWriter writer = new AssetObjectWriter(new AssetContentPolicy(), storage);
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

        AssetObjectDescriptor descriptor = writer.validateAndStore(
                new AssetUploadContent(AssetKind.LOGO, "image/png", "png", png));

        ArgumentCaptor<AssetObject> stored = ArgumentCaptor.forClass(AssetObject.class);
        verify(storage).putImmutable(stored.capture());
        assertThat(stored.getValue().descriptor()).isEqualTo(descriptor);
        assertThat(descriptor.key().value()).isEqualTo(
                "assets/logo/" + AssetSha256.calculate(png).value() + ".png");
    }

    @Test
    void invalidContentNeverReachesStorage() {
        AssetObjectStorage storage = mock(AssetObjectStorage.class);
        AssetObjectWriter writer = new AssetObjectWriter(new AssetContentPolicy(), storage);

        assertThatThrownBy(() -> writer.validateAndStore(
                        new AssetUploadContent(AssetKind.LOGO, "image/png", "png", new byte[] {1})))
                .isInstanceOf(InvalidAssetContentException.class);
        verifyNoInteractions(storage);
    }
}
