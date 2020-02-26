package org.cache.impl.tri;

import org.jgroups.Address;
import org.jgroups.Global;
import org.jgroups.util.Bits;
import org.jgroups.util.SizeStreamable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Class which wraps Data[] arrays */
public class DataBatch implements SizeStreamable, Runnable, Iterable<Data> {
    protected Address             addr;    // destination or sender of the data batch, may be null (e.g. on BACKUPs
    protected Data[]              data;    // the actual data
    protected int                 pos;     // the index to write the next Data element into the array
    protected Consumer<DataBatch> handler; // the code that gets executed in run() (if set)

    public DataBatch(Address addr) {
        this.addr=addr;
    }

    public DataBatch(Address addr, int capacity) {
        this.addr=addr;
        this.data=new Data[capacity];
    }

    public Data[]    data()                         {return data;}
    public int       capacity()                     {return data.length;}
    public int       position()                     {return pos;}
    public DataBatch handler(Consumer<DataBatch> h) {this.handler=h; return this;}


    public DataBatch add(Data d) {
        if(data == null)
            return this;
        if(pos >= data.length)
            data=Arrays.copyOf(data, data.length+1);
        this.data[pos++]=d;
        return this;
    }

    public void run() {
        if(handler != null)
            handler.accept(this);
    }

    public int size() {
        int size=0;
        for(int i=0; i < pos; i++) {
            if(data[i] != null)
                size++;
        }
        return size;
    }

    public int size(Data.Type t) {
        int size=0;
        for(int i=0; i < pos; i++) {
            if(data[i] != null && data[i].type == t)
                size++;
        }
        return size;
    }

    public boolean isEmpty() {
        for(int i=0; i < pos; i++) {
            if(data[i] != null)
                return false;
        }
        return true;
    }

    public int serializedSize() {
        int retval=Global.INT_SIZE;
        for(int i=0; i < pos; i++) {
            if(data[i] != null)
                retval+=data[i].serializedSize();
        }
        return retval;
    }

    /** Get the serialized size for all data elements of type t */
    public int serializedSize(Data.Type t) {
        int retval=Global.INT_SIZE;
        for(int i=0; i < pos; i++) {
            if(data[i] != null && data[i].type == t)
                retval+=data[i].serializedSize();
        }
        return retval;
    }

    public void writeTo(DataOutput out) throws IOException {
        Bits.writeInt(size(), out);
        for(int i=0; i < pos; i++) {
            if(data[i] != null)
                data[i].writeTo(out);
        }
    }

    public void writeTo(DataOutput out, Data.Type t) throws Exception {
        Bits.writeInt(size(t), out);
        for(int i=0; i < pos; i++) {
            if(data[i] != null && data[i].type == t)
                data[i].writeTo(out);
        }
    }

    public void readFrom(DataInput in) throws IOException, ClassNotFoundException {
        data=new Data[Bits.readInt(in)];
        for(int i=0; i < data.length; i++) {
            data[i]=new Data().read(in);
            pos++;
        }
    }

    /**
     * Counts the different types of Data elements
     * @param types Needs to be an array of: PUTs, GETS, ACKS, BACKUPs, CLEARs
     */
    public void count(int[] types) {
        for(int i=0; i < pos; i++) {
            if(data[i] == null)
                continue;
            switch(data[i].type) {
                case PUT:
                    types[0]++;
                    break;
                case GET:
                    types[1]++;
                    break;
                case ACK:
                    types[2]++;
                    break;
                case BACKUP:
                    types[3]++;
                    break;
                case CLEAR:
                    types[4]++;
                    break;
            }
        }
    }

    public String toString() {
        int[] types=new int[5];
        count(types);
        StringBuilder sb=new StringBuilder(String.format("addr=%s %d elements [cap=%d]: ", addr, size(), capacity()));
        if(types[0] > 0) sb.append(" ").append(types[0]).append(" puts");
        if(types[1] > 0) sb.append(" ").append(types[1]).append(" gets");
        if(types[2] > 0) sb.append(" ").append(types[2]).append(" acks");
        if(types[3] > 0) sb.append(" ").append(types[3]).append(" backups");
        if(types[4] > 0) sb.append(" ").append(types[4]).append(" clears");
        return sb.toString();
    }

    public Iterator<Data> iterator() {
        return new DataIterator(null);
    }

    public Iterator<Data> iterator(Data.Type type) {
        return new DataIterator(type);
    }


    public Stream<Data> stream() {
        Spliterator<Data> sp=Spliterators.spliterator(iterator(), size(), 0);
        return StreamSupport.stream(sp, false);
    }

    public Stream<Data> streamOf(Data.Type type) {
        Spliterator<Data> sp=Spliterators.spliterator(iterator(type), size(type), 0);
        return StreamSupport.stream(sp, false);
    }

    protected class DataIterator implements Iterator<Data> {
        protected int             index;
        protected final Data.Type type;

        public DataIterator(Data.Type type) {
            this.type=type;
        }

        public boolean hasNext() {
            while(index < pos) {
                if(data[index] != null && (type == null || data[index].type == type))
                    return true;
                index++;
            }
            return false;
        }

        public Data next() {
            if(index >= pos)
                throw new NoSuchElementException(String.format("index %d >= pos %d", index, pos));
            return data[index++];
        }
    }
}

