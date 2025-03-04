package org.cache.impl.tri;

import org.jgroups.Address;
import org.jgroups.Global;
import org.jgroups.util.Bits;
import org.jgroups.util.SizeStreamable;
import org.jgroups.util.Util;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.function.Consumer;

import static org.cache.impl.tri.Data.Type.BACKUP;
import static org.cache.impl.tri.TriCache.estimatedSizeOf;


/**
 * @author Bela Ban
 * @since  1.0
 */
public class Data<K,V> implements SizeStreamable, Runnable {
    protected Data.Type           type;
    protected long                req_id; // the ID of the request: unique per node
    protected K                   key;
    protected V                   value;
    protected Address             sender; // if type == BACKUP: the backup node needs to send an ACK to the original sender
    protected Consumer<Data<K,V>> handler;

    public Data() {
    }

    public Data(Data.Type type, long req_id, K key, V value, Address sender) {
        this.type=type;
        this.req_id=req_id;
        this.key=key;
        this.value=value;
        this.sender=sender;
    }

    public Data<K,V> sender(Address s)         {this.sender=s; return this;}
    public Data<K,V> handler(Consumer<Data<K,V>> h) {this.handler=h; return this;}

    public void run() {
        if(handler != null)
            handler.accept(this);
    }

    public int serializedSize() {
        int retval=Global.BYTE_SIZE;
        switch(type) {
            case PUT:    // req_id | key | value
            case BACKUP: // + original_sender
                retval+=Bits.size(req_id) + estimatedSizeOf(key) + estimatedSizeOf(value);
                if(type == BACKUP)
                    retval+=Util.size(sender);
                break;
            case GET: // req_id | key
                retval+=Bits.size(req_id) + estimatedSizeOf(key);
                break;
            case CLEAR:
                break;
            case ACK:    // req_id | value
                retval+=Bits.size(req_id) + estimatedSizeOf(value);
                break;
            default:
                throw new IllegalStateException(String.format("type %s not known", type));
        }
        return retval+2; // to be on the safe side
    }

    public void writeTo(DataOutput out) throws IOException {
        out.writeByte(type.ordinal());
        switch(type) {
            case PUT:    // req_id | key | value
            case BACKUP: // + original_sender
                Bits.writeLongCompressed(req_id, out);
                Util.objectToStream(key, out);
                Util.objectToStream(value, out);
                if(type == BACKUP)
                    Util.writeAddress(sender, out);
                break;
            case GET: // req_id | key
                Bits.writeLongCompressed(req_id, out);
                Util.objectToStream(key, out);
                break;
            case CLEAR:
                break;
            case ACK:    // req_id | value
                Bits.writeLongCompressed(req_id, out);
                Util.objectToStream(value, out);
                break;
            default:
                throw new IllegalStateException(String.format("type %s not known", type));
        }
    }

    public Data<K,V> read(DataInput in) throws IOException, ClassNotFoundException {
        readFrom(in);
        return this;
    }

    public void readFrom(DataInput in) throws IOException, ClassNotFoundException {
        type=Data.Type.get(in.readByte());
        switch(type) {
            case PUT:    // req_id | key | value
            case BACKUP: // + original_sender
                req_id=Bits.readLongCompressed(in);
                key=Util.objectFromStream(in);
                value=Util.objectFromStream(in);
                if(type == BACKUP)
                    sender=Util.readAddress(in);
                break;
            case GET: // req_id | key
                req_id=Bits.readLongCompressed(in);
                key=Util.objectFromStream(in);
                break;
            case CLEAR:
                break;
            case ACK:    // req_id | [value] (if used as GET response)
                req_id=Bits.readLongCompressed(in);
                value=Util.objectFromStream(in);
                break;
            default:
                throw new IllegalStateException(String.format("type %s not known", type));
        }
    }



    public String toString() {
        switch(type) {
            case PUT:
            case ACK:
                return String.format("%s req-id=%d", type, req_id);
            case GET:
                return String.format("%s key=%s req-id=%d", type, key, req_id);
            case CLEAR:
                return type.toString();
            case BACKUP:
                return String.format("%s req-id=%d caller=%s", type, req_id, sender);
            default:
                return "n/a";
        }
    }

    public enum Type {
        PUT, GET, ACK, BACKUP, CLEAR;
        private static final Type[] values=Type.values();
        public static Type get(int ordinal) {return values[ordinal];}
    }
}
