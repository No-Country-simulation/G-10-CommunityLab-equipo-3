package com.nocountry.simulation.communitylab.domain.enums;

public enum MessageType {
    DUDA("Duda o pregunta técnica"),
    TESTIMONIO("Testimonio de experiencia o impacto"),
    COMENTARIO("Comentario general o interacción"),
    QUEJA("Queja, reclamo o frustración"),
    SUGERENCIA("Sugerencia de contenido o mejora"),
    LOGRO("Logro personal, certificación o empleo"),
    OTRO("Otro tipo de mensaje");

    private final String description;

    MessageType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    //Validate Input
    public static MessageType parse (String value){
        if(value == null || value.isBlank()){
            return OTRO;
        }
        try{
            return MessageType.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e){
            return OTRO;
        }
    }
}
