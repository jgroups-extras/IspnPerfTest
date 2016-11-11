package org.cache.impl.tri;

/**
 * @author Bela Ban
 * @since  1.0
 */
public class Ref<T> {
    protected T ref;

    public Ref() {
    }

    public Ref(T ref) {
        this.ref=ref;
    }

    public boolean isNull()         {return ref == null;}
    public T       ref()            {return ref;}
    public Ref<T>  set(T new_value) {this.ref=new_value; return this;}
}
