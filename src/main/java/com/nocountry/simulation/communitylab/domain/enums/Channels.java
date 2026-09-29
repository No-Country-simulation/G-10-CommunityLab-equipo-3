package com.nocountry.simulation.communitylab.domain.enums;

public enum Channels {
    LINKEDIN("Post para Linkedin"),
    X ("Post para X"),
    NEWSLETTER ("Post como una newletter"),
    FAQ ("Duda o queja curada de la comunidad, sin respuesta");


    private final String description;

    Channels(String description){
        this.description = description;
    }

    //Validate Input
    public static Channels parse (String value){
        if(value == null || value.isBlank()){
            return FAQ;
        }
        try{
            return Channels.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e){
            return FAQ;
        }
    }
}
