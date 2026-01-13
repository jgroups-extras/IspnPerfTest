package org.perf;

import org.jgroups.util.SizeStreamable;
import org.jgroups.util.Util;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * @author Bela Ban
 * @since x.y
 */
public class Person implements SizeStreamable {
    protected int age;
    protected String name;
    protected static final short ID=1051;


    public Person() {
    }

    public Person(int age, String name) {
        this.age=age;
        this.name=name;
    }

    @Override
    public int serializedSize() {
        return Integer.BYTES + Util.size(name);
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        out.writeInt(age);
        Util.writeString(name, out);
    }

    @Override
    public void readFrom(DataInput in) throws IOException, ClassNotFoundException {
        age=in.readInt();
        name=Util.readString(in);
    }

    @Override
    public String toString() {
        return "Person{" +
          "age=" + age +
          ", name='" + name + '\'' +
          '}';
    }
}
