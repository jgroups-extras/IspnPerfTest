package org.perf;

import org.infinispan.commons.configuration.ClassAllowList;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.io.ByteBuffer;
import org.infinispan.commons.io.ByteBufferImpl;
import org.infinispan.commons.marshall.AbstractMarshaller;
import org.jgroups.util.ByteArray;
import org.jgroups.util.Streamable;
import org.jgroups.util.Util;

import java.io.IOException;
import java.util.Collections;

/**
 * @author Bela Ban
 * @since x.y
 */
public class PersonMarshaller extends AbstractMarshaller {
    final ClassAllowList allowList;

    public PersonMarshaller() {
        this(new ClassAllowList(Collections.emptyList()));
    }

    public PersonMarshaller(ClassAllowList allowList) {
        this.allowList=allowList;
    }

    public void initialize(ClassAllowList classAllowList) {
        this.allowList.read(classAllowList);
    }

    protected ByteBuffer objectToBuffer(Object o, int estimatedSize) throws IOException {
        ByteArray buf=Util.objectToBuffer(o);
        return ByteBufferImpl.create(buf.array(), 0, buf.length());
    }

    public Object objectFromByteBuffer(byte[] buf, int offset, int length) throws IOException, ClassNotFoundException {
        return Util.objectFromByteBuffer(buf, offset, length);
    }

    public boolean isMarshallable(Object o) {
        return o instanceof Streamable;
    }

    public MediaType mediaType() {
        return MediaType.APPLICATION_SERIALIZED_OBJECT;
    }
}