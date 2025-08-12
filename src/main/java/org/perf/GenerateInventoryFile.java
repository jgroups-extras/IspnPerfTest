package org.perf;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates ansible/gcp.yaml from ansible/gcp.yml.template
 * @author Bela Ban
 * @since  1.0.0
 */
public class GenerateInventoryFile {
    protected String in="ansible/gcp.yml.template", out="ansible/gcp.yml";

    public GenerateInventoryFile input(String s) {
        this.in=s; return this;
    }

    public GenerateInventoryFile output(String s) {
        this.out=s; return this;
    }

    public void generate() throws IOException {
        if(in.equals(out))
            throw new IllegalArgumentException(String.format("input file (%s) and output file (%s) cannot be the same",
                                                             in, out));
        List<String> ips=new ArrayList<>(20);
        /*  if(System.in.available() == 0) {
            System.out.println("-- nothing to read from stdin, terminating");
            return;
        }*/
        try(BufferedReader input=new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while((line=input.readLine()) != null)
                ips.add(line);
        }
        catch(IOException e) {
            e.printStackTrace();
        }

        String input_str=Files.readString(Path.of(in));
        for(int i=0; i < ips.size(); i++) {
            String variable="$" + i;
            input_str=input_str.replace(variable, ips.get(i));
        }
        System.out.printf("## created %s from %s: :\n## --------------------\n%s\n", out, in,
                          input_str);
        try(FileOutputStream output=new FileOutputStream(out)) {
            output.write(input_str.getBytes());
        }
    }

    public static void main(String[] args) throws IOException {
        GenerateInventoryFile gi=new GenerateInventoryFile();
        for(int i=0; i < args.length; i++) {
            if("-in".equals(args[i])) {
                gi.input(args[++i]);
                continue;
            }
            if("-out".equals(args[i])) {
                gi.output(args[++i]);
                continue;
            }
            help();
            return;
        }

        gi.generate();
    }

    protected static void help() {
        System.out.printf("%s [-in file] [-out file]\n", GenerateInventoryFile.class.getSimpleName());
    }
}
