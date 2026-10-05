document.addEventListener('DOMContentLoaded',()=>{
  document.querySelectorAll('[data-modal-open]').forEach(button=>button.addEventListener('click',()=>document.getElementById(button.dataset.modalOpen)?.showModal()));
  document.querySelectorAll('[data-modal-close]').forEach(button=>button.addEventListener('click',()=>button.closest('dialog')?.close()));
  document.querySelectorAll('dialog').forEach(dialog=>dialog.addEventListener('click',event=>{if(event.target===dialog)dialog.close()}));
  document.querySelectorAll('[data-copy]').forEach(button=>button.addEventListener('click',async()=>{const text=document.getElementById(button.dataset.copy)?.textContent||'';try{await navigator.clipboard.writeText(text);button.textContent='Copied'}catch{button.textContent='Select key above'}}));
});
