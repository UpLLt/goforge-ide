package dev.goforge.remote;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bounded Content-Length framing used by the production bridge and its real SSH probe. */
public final class GoRemoteLspFraming {
    public static final int LIMIT=16*1024*1024;
    private GoRemoteLspFraming(){}
    public static JsonObject read(InputStream input)throws IOException {
        int length=-1,total=0;boolean any=false;
        while(true) {
            var line=new ByteArrayOutputStream();int c;
            while((c=input.read())!=-1&&c!='\n') {any=true;if(++total>8192)throw new IOException("LSP header is too large.");line.write(c);}
            if(c==-1) {if(!any)return null;throw new EOFException("Incomplete LSP header.");}
            String header=line.toString(StandardCharsets.US_ASCII).strip();
            if(header.isEmpty())break;
            int colon=header.indexOf(':');
            if(colon<0)throw new IOException("Invalid LSP header; check SSH shell startup output.");
            if(header.substring(0,colon).equalsIgnoreCase("Content-Length")) {
                if(length!=-1)throw new IOException("Duplicate LSP Content-Length.");
                try{length=Integer.parseInt(header.substring(colon+1).trim());}catch(NumberFormatException e){throw new IOException("Invalid LSP length.");}
            }
        }
        if(length<0||length>LIMIT)throw new IOException("LSP frame exceeds limit or lacks length.");
        byte[] bytes=input.readNBytes(length);if(bytes.length!=length)throw new EOFException("Incomplete LSP body.");
        try{return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}
        catch(RuntimeException e){throw new IOException("Invalid LSP JSON.",e);}
    }
    public static void write(OutputStream output,JsonObject message)throws IOException {
        byte[] body=message.toString().getBytes(StandardCharsets.UTF_8);
        if(body.length>LIMIT)throw new IOException("LSP frame exceeds limit.");
        synchronized(output){output.write(("Content-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();}
    }
}
